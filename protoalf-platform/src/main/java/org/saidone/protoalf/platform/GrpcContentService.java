package org.saidone.protoalf.platform;

import com.google.protobuf.ByteString;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import lombok.val;
import org.alfresco.repo.rendition2.SynchronousTransformClient;
import org.alfresco.service.ServiceRegistry;
import org.alfresco.service.cmr.repository.ContentService;
import org.saidone.protoalf.api.*;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Exposes document content access and streaming transfers through gRPC.
 *
 * <p>{@link #getReader} returns a byte slice or extracted UTF-8 text, and {@link #getWriter} replaces document
 * content. Unlike local Alfresco readers and writers, these RPCs exchange bytes rather than live Java handles.
 * The streaming extensions download stored bytes and upload new documents larger than a unary message can hold.
 *
 * <p>Downloads resolve the reader in a read-only transaction, then stream outside that transaction to avoid
 * duplicate output on transaction retries. Uploads spool bytes to a temporary file and create the document in a
 * retryable read-write transaction only after the client completes. Repository work always runs as the
 * authenticated caller.
 *
 * @see ContentService
 */
@Slf4j
public final class GrpcContentService extends ContentServiceGrpc.ContentServiceImplBase {

    /**
     * Shared caller-context, transaction, reference-resolution and error-mapping helper.
     */
    private final GrpcServiceSupport support;

    /**
     * Converter from repository state to transport-safe node snapshots.
     */
    private final NodeMapper mapper;

    /**
     * Validated repository mutation helper used inside write transactions.
     */
    private final NodeWriter writer;

    /**
     * Stored-content reader and text-extraction helper.
     */
    private final ContentFetcher contents;

    /**
     * Creates the content adapter; non-plain-text extraction is unavailable without a transform client.
     *
     * @param services Alfresco service registry; repository work uses its secured services
     */
    public GrpcContentService(ServiceRegistry services) {
        this(services, null);
    }

    /**
     * Creates the adapter and its repository helpers without starting repository work.
     *
     * @param services Alfresco service registry; repository work uses its secured services
     * @param transforms synchronous text-transform client, or null to disable non-plain-text extraction
     */
    public GrpcContentService(ServiceRegistry services, SynchronousTransformClient transforms) {
        this.support = new GrpcServiceSupport(services);
        this.mapper = new NodeMapper(services);
        this.writer = new NodeWriter(services);
        this.contents = new ContentFetcher(services, transforms);
    }

    /**
     * Maximum total upload payload: 2 GiB, excluding header metadata and message framing.
     */
    static final long MAX_UPLOAD_BYTES = 2L * 1024 * 1024 * 1024;

    /**
     * Maximum download data chunk size: 1 MiB, excluding Protobuf framing.
     */
    static final int DOWNLOAD_CHUNK_SIZE = 1024 * 1024;

    /**
     * Returns a bounded slice of stored bytes or extracted UTF-8 text. Offsets are byte offsets in the selected
     * representation. A zero limit selects 2 MiB; explicit limits must be 1..2 MiB. Text slices avoid ending
     * inside a UTF-8 character.
     *
     * <p>A missing transform facility or unsupported input format yields {@code FAILED_PRECONDITION}; transform
     * content failures yield {@code UNAVAILABLE}. This RPC materializes bytes rather than returning an Alfresco
     * reader handle.
     *
     * @param request document reference, text-extraction flag, byte offset and slice limit
     * @param observer receives one content slice with total size and continuation flag, or a gRPC error
     */
    @Override public void getReader(GetContentRequest request, StreamObserver<GetContentResponse> observer) {
        support.execute(observer, () -> contents.read(support.ref(request.getNodeRef()), request.getText(), request.getOffset(), request.getMaxBytes()));
    }

    /**
     * Replaces the stored bytes of an existing document and returns its updated metadata. Content must be
     * supplied; empty bytes are allowed. A blank MIME type is guessed from the node name and a blank encoding
     * defaults to UTF-8.
     *
     * <p>This remote adaptation performs the write immediately rather than returning an Alfresco writer handle.
     * The request must fit the configured inbound message size.
     *
     * @param request document reference and required content bytes with optional MIME type and encoding
     * @param observer receives one updated document snapshot, or a gRPC error
     */
    @Override public void getWriter(UpdateContentRequest request, StreamObserver<Node> observer) {
        support.write(observer, () -> mapper.map(writer.updateContent(support.ref(request.getNodeRef()), request.hasContent() ? request.getContent() : null)));
    }

    /**
     * Streams stored document bytes without text extraction. The response starts with content metadata,
     * followed by data messages of at most 1 MiB. A document without stored content emits only metadata.
     *
     * <p>The reader is resolved transactionally as the caller; bytes are emitted afterwards so transaction
     * retries cannot duplicate output. I/O failures yield {@code INTERNAL}; client cancellation stops the
     * transfer.
     *
     * @param request document or frozen-version node reference to download
     * @param observer receives metadata followed by ordered byte chunks, then completion, or a gRPC error
     */
    @Override public void downloadContent(DownloadContentRequest request, StreamObserver<DownloadContentResponse> observer) {
        val user = TicketInterceptor.USER.get();
        if (user == null) {
            observer.onError(Status.UNAUTHENTICATED.asRuntimeException());
            return;
        }
        ContentFetcher.ContentStream content;
        try {
            // Only the lookup is transactional: the bytes are streamed after it, so a retry cannot duplicate output.
            content = support.transact(user, true, () -> contents.open(support.ref(request.getNodeRef())));
        } catch (StatusRuntimeException e) {
            observer.onError(e);
            return;
        }
        stream(content, observer);
    }

    /**
     * Sends download metadata and stored byte chunks, closing the opened repository stream. Client cancellation
     * is logged and stops processing; other transfer failures are reported as INTERNAL.
     *
     * @param content metadata and optional reader resolved inside the caller transaction
     * @param observer receives metadata, byte chunks and the terminal outcome
     */
    private void stream(ContentFetcher.ContentStream content, StreamObserver<DownloadContentResponse> observer) {
        try {
            observer.onNext(DownloadContentResponse.newBuilder().setInfo(content.info).build());
            if (content.reader != null) {
                try (val in = content.reader.getContentInputStream()) {
                    val buffer = new byte[DOWNLOAD_CHUNK_SIZE];
                    for (int n = in.read(buffer); n != -1; n = in.read(buffer)) observer.onNext(DownloadContentResponse.newBuilder().setData(ByteString.copyFrom(buffer, 0, n)).build());
                }
            }
            observer.onCompleted();
        } catch (IOException | RuntimeException e) {
            if (Status.fromThrowable(e).getCode() == Status.Code.CANCELLED) {
                log.debug("Download cancelled by the client");
                return;
            }
            log.error("Content download failed", e);
            observer.onError(Status.INTERNAL.withDescription("Content download failed").asRuntimeException());
        }
    }

    /**
     * Creates a new document from a client stream consisting of one header followed by data chunks.
     *
     * <p>Bytes are spooled before repository creation; no node is created until the client completes. The
     * maximum total payload is {@link #MAX_UPLOAD_BYTES}. Protocol violations and excess size yield {@code
     * INVALID_ARGUMENT}; missing caller identity yields {@code UNAUTHENTICATED}.
     *
     * @param observer receives one created document after commit, or a gRPC error
     * @return a request observer that consumes the header and content chunks
     */
    @Override public StreamObserver<UploadContentRequest> uploadContent(StreamObserver<Node> observer) {
        return upload(observer, MAX_UPLOAD_BYTES);
    }

    /**
     * Creates an upload receiver with an explicit byte limit, allowing boundary cases to be tested without
     * large files.
     *
     * @param observer response observer for the created node or terminal error
     * @param maxBytes maximum total bytes accepted after the header
     * @return the upload receiver, or an inert observer after reporting missing authentication
     */
    StreamObserver<UploadContentRequest> upload(StreamObserver<Node> observer, long maxBytes) {
        val user = TicketInterceptor.USER.get();
        if (user == null) {
            observer.onError(Status.UNAUTHENTICATED.asRuntimeException());
            return new StreamObserver<>() {

                @Override public void onNext(UploadContentRequest value) {
                }

                @Override public void onError(Throwable t) {
                }

                @Override public void onCompleted() {
                }
            };
        }
        return new Upload(observer, user, maxBytes);
    }

    /**
     * Receives one document upload, spooling bytes before the transactional repository write.
     *
     * <p>The first message must be a header and every later message must contain data. A terminal state
     * prevents further processing after completion, cancellation or failure. Every terminal path attempts to
     * close and delete the spool file; cleanup errors are logged without replacing the original RPC outcome.
     */
    @RequiredArgsConstructor(access = AccessLevel.PRIVATE)
    private final class Upload implements StreamObserver<UploadContentRequest> {

        /**
         * Observer receiving the created node or a terminal gRPC error.
         */
        private final StreamObserver<Node> observer;

        /**
         * Authenticated username captured when the upload starts.
         */
        private final String user;

        /**
         * Maximum cumulative size of accepted data chunks.
         */
        private final long maxBytes;

        /**
         * First accepted header, or null until the initial message arrives.
         */
        private CreateDocumentHeader header;

        /**
         * Temporary spool path, or null before creation and after cleanup.
         */
        private Path file;

        /**
         * Open spool output, or null before allocation and after cleanup.
         */
        private OutputStream out;

        /**
         * Number of content bytes accepted after the header.
         */
        private long size;

        /**
         * Whether a terminal success, failure or cancellation has already been processed.
         */
        private boolean done;

        /**
         * Accepts a header or appends a data chunk to the spool file. Invalid order, empty messages or an
         * exceeded byte limit terminate the upload and clean up temporary resources.
         *
         * @param request next upload message; the first must contain a header
         */
        @Override public void onNext(UploadContentRequest request) {
            if (done)
                return;
            try {
                if (header == null) {
                    if (request.getPartCase() != UploadContentRequest.PartCase.HEADER) {
                        fail(Status.INVALID_ARGUMENT.withDescription("The first message must be the header"));
                        return;
                    }
                    header = request.getHeader();
                    file = Files.createTempFile("alfresco-upload-", ".tmp");
                    out = Files.newOutputStream(file);
                } else if (request.getPartCase() == UploadContentRequest.PartCase.HEADER) {
                    fail(Status.INVALID_ARGUMENT.withDescription("The header must be the first message only"));
                } else if (request.getPartCase() != UploadContentRequest.PartCase.DATA) {
                    fail(Status.INVALID_ARGUMENT.withDescription("Empty message: send data chunks after the header"));
                } else {
                    if (request.getData().size() > maxBytes - size) {
                        fail(Status.INVALID_ARGUMENT.withDescription(String.format("Content exceeds %d bytes", maxBytes)));
                        return;
                    }
                    size += request.getData().size();
                    request.getData().writeTo(out);
                }
            } catch (IOException e) {
                log.error("Cannot spool the uploaded content", e);
                fail(Status.INTERNAL.withDescription("Upload failed"));
            }
        }

        /**
         * Closes the spool and creates the document in a retryable write transaction. The file can be reopened
         * on every attempt. The response is emitted after the transaction completes; the spool is cleaned up
         * even if creation fails.
         */
        @Override public void onCompleted() {
            if (done)
                return;
            if (header == null) {
                fail(Status.INVALID_ARGUMENT.withDescription("The header is required"));
                return;
            }
            done = true;
            val spool = file;
            Status failure = null;
            Node node = null;
            try {
                out.close();
                node = support.transact(user, false, () -> mapper.map(writer.createDocument(support.ref(header.getParentRef()), header.getName(), header.getType(), header.getPropertiesMap(), header.getAspectsList(), header.getMimetype(), header.getEncoding(), spool)));
            } catch (IOException e) {
                log.error("Cannot close the upload spool", e);
                failure = Status.INTERNAL.withDescription("Upload failed");
            } catch (StatusRuntimeException e) {
                failure = e.getStatus();
            } finally {
                cleanup();
            }
            if (failure != null)
                observer.onError(failure.asRuntimeException());
            else {
                observer.onNext(node);
                observer.onCompleted();
            }
        }

        /**
         * Handles client cancellation or a broken input stream by entering the terminal state and deleting the
         * spool. No response is sent to a client that has already ended the call.
         *
         * @param t client-side or transport failure
         */
        @Override public void onError(Throwable t) {
            // Cancelled or broken by the client: nothing to report back.
            if (done)
                return;
            done = true;
            log.debug("Upload ended by the client", t);
            cleanup();
        }

        /**
         * Terminates the upload, releases its temporary resources and reports one gRPC error.
         *
         * @param status terminal failure status to send to the response observer
         */
        private void fail(Status status) {
            done = true;
            cleanup();
            observer.onError(status.asRuntimeException());
        }

        /**
         * Closes the spool output and deletes the temporary file, if present. Repeated calls are safe; cleanup
         * failures are logged and do not replace the original RPC result.
         */
        private void cleanup() {
            try {
                if (out != null)
                    out.close();
            } catch (IOException e) {
                log.debug("Cannot close the upload spool", e);
            } finally {
                out = null;
            }
            try {
                if (file != null)
                    Files.deleteIfExists(file);
            } catch (IOException e) {
                log.warn("Cannot delete the upload spool {}", file, e);
            } finally {
                file = null;
            }
        }
    }
}

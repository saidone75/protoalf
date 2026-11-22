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
import org.saidone.protoalf.api.*;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/** Adapts Alfresco ContentService using content slices, byte writes and streaming rather than local reader/writer handles. */
@Slf4j
public final class GrpcContentService extends ContentServiceGrpc.ContentServiceImplBase {

    private final GrpcServiceSupport support;
    private final NodeMapper mapper;
    private final NodeWriter writer;
    private final ContentFetcher contents;

    public GrpcContentService(ServiceRegistry services) {
        this(services, null);
    }

    public GrpcContentService(ServiceRegistry services, SynchronousTransformClient transforms) {
        this.support = new GrpcServiceSupport(services);
        this.mapper = new NodeMapper(services);
        this.writer = new NodeWriter(services);
        this.contents = new ContentFetcher(services, transforms);
    }

    /**
     * Largest content UploadContent accepts, in bytes: larger uploads are refused with INVALID_ARGUMENT.
     */
    static final long MAX_UPLOAD_BYTES = 2L * 1024 * 1024 * 1024;

    /**
     * Largest data message DownloadContent sends.
     */
    static final int DOWNLOAD_CHUNK_SIZE = 1024 * 1024;

    @Override public void getReader(GetContentRequest request, StreamObserver<GetContentResponse> observer) {
        support.execute(observer, () -> contents.read(support.ref(request.getNodeRef()), request.getText(), request.getOffset(), request.getMaxBytes()));
    }

    @Override public void getWriter(UpdateContentRequest request, StreamObserver<Node> observer) {
        support.write(observer, () -> mapper.map(writer.updateContent(support.ref(request.getNodeRef()), request.hasContent() ? request.getContent() : null)));
    }

    /**
     * Streams the stored bytes of a document: one info message, then data messages of at most DOWNLOAD_CHUNK_SIZE.
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
     * Creates a document from a stream of messages: the header first, then the data chunks.
     */
    @Override public StreamObserver<UploadContentRequest> uploadContent(StreamObserver<Node> observer) {
        return upload(observer, MAX_UPLOAD_BYTES);
    }

    /**
     * @param maxBytes largest accepted content: MAX_UPLOAD_BYTES, lower in tests
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
     * Spools the data to a temporary file, then creates the document in one read-write transaction when the client
     * completes. The file is deleted however the call ends. Protocol violations are INVALID_ARGUMENT.
     */
    @RequiredArgsConstructor(access = AccessLevel.PRIVATE)
    private final class Upload implements StreamObserver<UploadContentRequest> {

        private final StreamObserver<Node> observer;

        private final String user;

        private final long maxBytes;

        private CreateDocumentHeader header;

        private Path file;

        private OutputStream out;

        private long size;

        private boolean done;

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
                node = support.transact(user, false, () -> mapper.map(writer.createDocument(support.ref(header.getParentRef()), header.getName(), header.getType(), header.getPropertiesMap(), GrpcServiceSupport.values(header.getPropertyValuesMap()), header.getAspectsList(), header.getMimetype(), header.getEncoding(), spool)));
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

        @Override public void onError(Throwable t) {
            // Cancelled or broken by the client: nothing to report back.
            if (done)
                return;
            done = true;
            log.debug("Upload ended by the client", t);
            cleanup();
        }

        private void fail(Status status) {
            done = true;
            cleanup();
            observer.onError(status.asRuntimeException());
        }

        /**
         * Closes and deletes the spool file. Idempotent.
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

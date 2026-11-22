package org.saidone.protoalf.client;

import com.google.protobuf.ByteString;
import io.grpc.*;
import io.grpc.stub.ClientCallStreamObserver;
import io.grpc.stub.ClientResponseObserver;
import lombok.val;
import org.saidone.protoalf.api.*;
import org.saidone.protoalf.api.ContentServiceGrpc.ContentServiceBlockingStub;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Client of the platform's content service: byte slices, writes, downloads and streaming document creation.
 *
 * <p>Each call sends the shared provider's Alfresco ticket and a per-call deadline. Unary calls retry once
 * after UNAUTHENTICATED if the provider supplies a different ticket. The caller owns the channel and should
 * share one TicketProvider across service clients.
 *
 * <p>Downloads retry only before any payload bytes have been written. Uploads are never replayed because the
 * supplied input stream may not be rewindable.
 */
public class ContentServiceClient {

    private final ContentServiceBlockingStub stub;
    private final ClientCallSupport support;

    /**
     * Creates this service client with shared authentication and a per-call deadline.
     *
     * <p>The caller retains ownership of the channel; closing an application should close its shared channel
     * once.
     *
     * @param channel shared gRPC channel; ownership remains with the caller
     * @param tickets shared ticket provider, normally one AuthenticationServiceClient
     * @param deadline per-call deadline applied to every RPC attempt
     */
    public ContentServiceClient(Channel channel, TicketProvider tickets, Duration deadline) {
        this(ContentServiceGrpc.newBlockingStub(channel), tickets, deadline);
    }

    /**
     * Creates this service client with shared authentication and a per-call deadline.
     *
     * <p>The caller retains ownership of the channel; closing an application should close its shared channel
     * once.
     *
     * @param stub preconfigured stub for this repository service
     * @param tickets shared ticket provider, normally one AuthenticationServiceClient
     * @param deadline per-call deadline applied to every RPC attempt
     */
    public ContentServiceClient(ContentServiceBlockingStub stub, TicketProvider tickets, Duration deadline) {
        this.stub = stub;
        this.support = new ClientCallSupport(tickets, deadline);
    }

    /**
     * Chunk size of UploadContent messages.
     */
    private static final int UPLOAD_CHUNK_SIZE = 1024 * 1024;

    /**
     * Reads a bounded slice of stored content or extracted UTF-8 text, including continuation metadata.
     *
     * @param nodeRef node reference, bare workspace ID or supported repository alias
     * @param text true for extracted UTF-8 text, false for the original stored bytes
     * @param offset non-negative byte offset in the selected stored/text representation
     * @param maxBytes maximum slice bytes; zero selects the server limit
     * @return the content slice, total representation size and continuation flag
     */
    public GetContentResponse getReader(String nodeRef, boolean text, long offset, int maxBytes) {
        return support.call(stub, s -> s.getReader(GetContentRequest.newBuilder().setNodeRef(nodeRef).setText(text).setOffset(offset).setMaxBytes(maxBytes).build()));
    }

    /**
     * Replaces an existing document's content immediately rather than returning a local writer handle.
     *
     * @param nodeRef node reference, bare workspace ID or supported repository alias
     * @param content document bytes and metadata; optional only on creation
     * @return the resulting node snapshot
     */
    public Node getWriter(String nodeRef, Content content) {
        return support.call(stub, s -> s.getWriter(UpdateContentRequest.newBuilder().setNodeRef(nodeRef).setContent(content).build()));
    }

    /**
     * Streams the stored bytes of a document into {@code out}, which is not closed. The deadline covers the
     * whole download. On UNAUTHENTICATED the ticket is renewed and the download retried once, but only while
     * nothing has been written to {@code out} yet; otherwise the {@link StatusRuntimeException} is rethrown.
     *
     * @param nodeRef node reference, bare workspace ID or supported repository alias
     * @param out destination stream, which remains open and owned by the caller
     * @return the stored content metadata; missing content has zero size
     */
    public ContentInfo downloadContent(String nodeRef, OutputStream out) {
        val request = DownloadContentRequest.newBuilder().setNodeRef(nodeRef).build();
        val written = new AtomicLong();
        val ticket = support.ticket();
        try {
            return download(support.authenticated(stub, ticket), request, out, written);
        } catch (StatusRuntimeException e) {
            if (e.getStatus().getCode() != Status.Code.UNAUTHENTICATED || written.get() > 0)
                throw e;
            support.invalidate(ticket);
            val renewed = support.ticket();
            if (renewed.equals(ticket))
                throw e;
            return download(support.authenticated(stub, renewed), request, out, written);
        }
    }

    private ContentInfo download(ContentServiceBlockingStub s, DownloadContentRequest request, OutputStream out, AtomicLong written) {
        ContentInfo info = null;
        val parts = s.downloadContent(request);
        while (parts.hasNext()) {
            val part = parts.next();
            switch(part.getPartCase()) {
                case INFO ->
                    info = part.getInfo();
                case DATA ->
                    {
                        val data = part.getData();
                        written.addAndGet(data.size());
                        try {
                            data.writeTo(out);
                        } catch (IOException e) {
                            throw new UncheckedIOException("Cannot write the downloaded content", e);
                        }
                    }
                default ->
                    {
                    }
            }
        }
        if (info == null)
            throw new IllegalStateException("The server sent no content info");
        return info;
    }

    /**
     * Creates a document: the header first, then the bytes of {@code data} in chunks of at most 1 MB. Returns
     * the created node. The stream is read but not closed. <p> The deadline covers the whole upload, so it must
     * be generous for large files. An UNAUTHENTICATED answer drops the ticket (the next call logs in again) and
     * rethrows the {@link StatusRuntimeException}: this method is never retried, because the input stream
     * cannot be rewound. Open the stream again and call it again.
     *
     * @param header parent, name, type, properties, aspects and content metadata of the new document
     * @param data source stream; it is read once and remains owned by the caller
     * @return the resulting node snapshot
     */
    public Node uploadContent(CreateDocumentHeader header, InputStream data) {
        val ticket = support.ticket();
        val upload = new Upload();
        val sender = support.authenticated(ContentServiceGrpc.newStub(stub.getChannel()), ticket).uploadContent(upload);
        try {
            sender.onNext(UploadContentRequest.newBuilder().setHeader(header).build());
            val buffer = new byte[UPLOAD_CHUNK_SIZE];
            for (int n = data.read(buffer); n != -1 && !upload.response.isDone(); n = data.read(buffer)) {
                upload.awaitReady();
                if (upload.response.isDone())
                    break;
                sender.onNext(UploadContentRequest.newBuilder().setData(ByteString.copyFrom(buffer, 0, n)).build());
            }
            sender.onCompleted();
        } catch (IOException e) {
            sender.onError(Status.CANCELLED.withDescription("Cannot read the content to upload").withCause(e).asRuntimeException());
            throw new UncheckedIOException("Cannot read the content to upload", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            sender.onError(Status.CANCELLED.withDescription("Upload interrupted").withCause(e).asRuntimeException());
            throw new IllegalStateException("Interrupted while uploading", e);
        }
        try {
            return upload.response.get();
        } catch (ExecutionException e) {
            if (e.getCause() instanceof StatusRuntimeException status) {
                if (status.getStatus().getCode() == Status.Code.UNAUTHENTICATED)
                    support.invalidate(ticket);
                throw status;
            }
            throw new IllegalStateException("Upload failed", e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            sender.onError(Status.CANCELLED.withDescription("Upload interrupted").withCause(e).asRuntimeException());
            throw new IllegalStateException("Interrupted while uploading", e);
        }
    }

    /**
     * Receives the response of an upload and tells when the call accepts more messages (flow control).
     */
    private static final class Upload implements ClientResponseObserver<UploadContentRequest, Node> {

        final CompletableFuture<Node> response = new CompletableFuture<>();

        private final Object ready = new Object();

        private ClientCallStreamObserver<UploadContentRequest> requests;

        @Override public void beforeStart(ClientCallStreamObserver<UploadContentRequest> requests) {
            this.requests = requests;
            requests.setOnReadyHandler(this::wake);
        }

        @Override public void onNext(Node value) {
            response.complete(value);
        }

        @Override public void onError(Throwable t) {
            response.completeExceptionally(t);
            wake();
        }

        @Override public void onCompleted() {
            wake();
        }

        /**
         * Waits until the call is ready for another message, or has ended.
         */
        void awaitReady() throws InterruptedException {
            synchronized (ready) {
                while (!requests.isReady() && !response.isDone()) ready.wait(100);
            }
        }

        private void wake() {
            synchronized (ready) {
                ready.notifyAll();
            }
        }
    }
}

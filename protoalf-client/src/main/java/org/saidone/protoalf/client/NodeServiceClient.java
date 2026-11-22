package org.saidone.protoalf.client;

import com.google.protobuf.ByteString;
import io.grpc.*;
import io.grpc.stub.ClientCallStreamObserver;
import io.grpc.stub.ClientResponseObserver;
import io.grpc.stub.MetadataUtils;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.val;
import org.saidone.protoalf.api.*;
import org.saidone.protoalf.api.NodeServiceGrpc.NodeServiceBlockingStub;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Thin wrapper around the generated gRPC stub: attaches the Alfresco ticket to every call
 * and retries once with a fresh ticket when the server answers UNAUTHENTICATED (expired ticket).
 * Failures surface as {@link StatusRuntimeException} with the gRPC status of the module
 * (NOT_FOUND, PERMISSION_DENIED, ALREADY_EXISTS, INVALID_ARGUMENT, FAILED_PRECONDITION, ...).
 *
 * <pre>{@code
 * ManagedChannel channel = AlfrescoGrpcChannels.create("localhost", 9090, "ca.crt", null);
 * val tickets = new AuthenticationServiceClient(channel, "admin", "admin", Duration.ofSeconds(30));
 * val client = new NodeServiceClient(channel, tickets, Duration.ofSeconds(30));
 * Node home = client.getNode("-my-");
 * }</pre>
 */
@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
public class NodeServiceClient {

    /**
     * gRPC header carrying the Alfresco ticket.
     */
    public static final Metadata.Key<String> TICKET_HEADER = Metadata.Key.of("alfresco-ticket", Metadata.ASCII_STRING_MARSHALLER);

    /**
     * Chunk size of UploadContent messages.
     */
    private static final int UPLOAD_CHUNK_SIZE = 1024 * 1024;
    private final Channel channel;
    private final NodeServiceBlockingStub stub;
    private final TicketProvider tickets;
    private final Duration deadline;

    /**
     * @param deadline per-call deadline
     */
    public NodeServiceClient(Channel channel, TicketProvider tickets, Duration deadline) {
        this(channel, NodeServiceGrpc.newBlockingStub(channel), tickets, deadline);
    }

    public NodeServiceClient(NodeServiceBlockingStub stub, TicketProvider tickets, Duration deadline) {
        this(stub.getChannel(), stub, tickets, deadline);
    }

    public Node getNode(String nodeRef) {
        return call(s -> s.getNode(GetNodeRequest.newBuilder().setNodeRef(nodeRef).build()));
    }

    public ListChildrenResponse listChildren(String nodeRef, int skipCount, int maxItems) {
        return call(s -> s.listChildren(ListChildrenRequest.newBuilder().setNodeRef(nodeRef).setSkipCount(skipCount).setMaxItems(maxItems).build()));
    }

    /**
     * @param content null to create a folder or an empty document
     */
    public Node createNode(String parentRef, String name, String type, Map<String, String> properties, Content content) {
        return createNode(parentRef, name, type, properties, Map.of(), List.of(), content);
    }

    /**
     * @param properties     one text value per property
     * @param propertyValues values per property: several for a multi-valued property, empty to unset it.
     *                       A property must not appear in both maps
     * @param aspects        aspects added before the properties are set (already present ones are ignored)
     * @param content        null to create a folder or an empty document
     */
    public Node createNode(String parentRef, String name, String type, Map<String, String> properties, Map<String, List<String>> propertyValues, List<String> aspects, Content content) {
        val request = CreateNodeRequest.newBuilder().setParentRef(parentRef).setName(name).setType(type).putAllProperties(properties).putAllPropertyValues(toPropertyValues(propertyValues)).addAllAspects(aspects);
        if (content != null)
            request.setContent(content);
        return call(s -> s.createNode(request.build()));
    }

    public Node updateNode(String nodeRef, String name, Map<String, String> properties) {
        return updateNode(nodeRef, name, "", properties);
    }

    /**
     * @param type new type (e.g. a cm:content subtype), empty to keep the current one
     */
    public Node updateNode(String nodeRef, String name, String type, Map<String, String> properties) {
        return updateNode(nodeRef, name, type, properties, Map.of(), List.of(), List.of());
    }

    /**
     * @param type          new type (e.g. a cm:content subtype), empty to keep the current one
     * @param propertyValues values per property, see {@link #createNode(String, String, String, Map, Map, List, Content)}
     * @param addAspects    aspects to add (already present ones are ignored)
     * @param removeAspects aspects to remove (absent ones are ignored)
     */
    public Node updateNode(String nodeRef, String name, String type, Map<String, String> properties, Map<String, List<String>> propertyValues, List<String> addAspects, List<String> removeAspects) {
        return call(s -> s.updateNode(UpdateNodeRequest.newBuilder().setNodeRef(nodeRef).setName(name).setType(type).putAllProperties(properties).putAllPropertyValues(toPropertyValues(propertyValues)).addAllAddAspects(addAspects).addAllRemoveAspects(removeAspects).build()));
    }

    public Node moveNode(String nodeRef, String targetParentRef, String name) {
        return call(s -> s.moveNode(MoveNodeRequest.newBuilder().setNodeRef(nodeRef).setTargetParentRef(targetParentRef).setName(name).build()));
    }

    public Node copyNode(String nodeRef, String targetParentRef, String name) {
        return call(s -> s.copyNode(CopyNodeRequest.newBuilder().setNodeRef(nodeRef).setTargetParentRef(targetParentRef).setName(name).build()));
    }

    public void deleteNode(String nodeRef) {
        call(s -> s.deleteNode(DeleteNodeRequest.newBuilder().setNodeRef(nodeRef).build()));
    }

    private static Map<String, PropertyValues> toPropertyValues(Map<String, List<String>> values) {
        return values.entrySet().stream().collect(Collectors.toMap(Map.Entry::getKey, e -> PropertyValues.newBuilder().addAllValues(e.getValue()).build()));
    }

    /**
     * Version history of a document, newest first; empty when it is not versionable. Pass the version_ref of a version
     * to {@link #getContent} or {@link #downloadContent} to read that version.
     */
    public ListVersionsResponse listVersions(String nodeRef) {
        return call(s -> s.listVersions(ListVersionsRequest.newBuilder().setNodeRef(nodeRef).build()));
    }

    public GetContentResponse getContent(String nodeRef, boolean text, long offset, int maxBytes) {
        return call(s -> s.getContent(GetContentRequest.newBuilder().setNodeRef(nodeRef).setText(text).setOffset(offset).setMaxBytes(maxBytes).build()));
    }

    public Node updateContent(String nodeRef, Content content) {
        return call(s -> s.updateContent(UpdateContentRequest.newBuilder().setNodeRef(nodeRef).setContent(content).build()));
    }

    /**
     * Streams the stored bytes of a document into {@code out}, which is not closed. The deadline covers the whole
     * download. On UNAUTHENTICATED the ticket is renewed and the download retried once, but only while nothing has
     * been written to {@code out} yet; otherwise the {@link StatusRuntimeException} is rethrown.
     *
     * @return the mimetype, encoding and size of the content (size 0 and no bytes for a document without content)
     */
    public ContentInfo downloadContent(String nodeRef, OutputStream out) {
        val request = DownloadContentRequest.newBuilder().setNodeRef(nodeRef).build();
        val written = new AtomicLong();
        val ticket = tickets.ticket();
        try {
            return download(authenticated(ticket), request, out, written);
        } catch (StatusRuntimeException e) {
            if (e.getStatus().getCode() != Status.Code.UNAUTHENTICATED || written.get() > 0)
                throw e;
            tickets.invalidate(ticket);
            val renewed = tickets.ticket();
            if (renewed.equals(ticket))
                throw e;
            return download(authenticated(renewed), request, out, written);
        }
    }

    private ContentInfo download(NodeServiceBlockingStub s, DownloadContentRequest request, OutputStream out, AtomicLong written) {
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
     * Creates a document: the header first, then the bytes of {@code data} in chunks of at most 1 MB. Returns the
     * created node. The stream is read but not closed.
     * <p>
     * The deadline covers the whole upload, so it must be generous for large files. An UNAUTHENTICATED answer drops
     * the ticket (the next call logs in again) and rethrows the {@link StatusRuntimeException}: this method is never
     * retried, because the input stream cannot be rewound. Open the stream again and call it again.
     */
    public Node uploadContent(CreateDocumentHeader header, InputStream data) {
        val ticket = tickets.ticket();
        val upload = new Upload();
        val sender = NodeServiceGrpc.newStub(channel).withInterceptors(ticketHeader(ticket)).withDeadlineAfter(deadline.toMillis(), TimeUnit.MILLISECONDS).uploadContent(upload);
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
                    tickets.invalidate(ticket);
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

        @Override
        public void beforeStart(ClientCallStreamObserver<UploadContentRequest> requests) {
            this.requests = requests;
            requests.setOnReadyHandler(this::wake);
        }

        @Override
        public void onNext(Node value) {
            response.complete(value);
        }

        @Override
        public void onError(Throwable t) {
            response.completeExceptionally(t);
            wake();
        }

        @Override
        public void onCompleted() {
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

    public SearchResponse search(String query, String language, int skipCount, int maxItems) {
        return search(query, language, skipCount, maxItems, List.of());
    }

    /**
     * @param sort sort criteria, applied in order (afts only); empty to sort by relevance
     */
    public SearchResponse search(String query, String language, int skipCount, int maxItems, List<SortField> sort) {
        return call(s -> s.search(SearchRequest.newBuilder().setQuery(query).setLanguage(language).setSkipCount(skipCount).setMaxItems(maxItems).addAllSort(sort).build()));
    }

    public ListModelsResponse listModels(String model, boolean includeProperties) {
        return call(s -> s.listModels(ListModelsRequest.newBuilder().setModel(model).setIncludeProperties(includeProperties).build()));
    }

    private <T> T call(Function<NodeServiceBlockingStub, T> rpc) {
        val ticket = tickets.ticket();
        try {
            return rpc.apply(authenticated(ticket));
        } catch (StatusRuntimeException e) {
            if (e.getStatus().getCode() != Status.Code.UNAUTHENTICATED)
                throw e;
            tickets.invalidate(ticket);
            val renewed = tickets.ticket();
            // the provider cannot renew it: retrying would fail again
            if (renewed.equals(ticket))
                throw e;
            return rpc.apply(authenticated(renewed));
        }
    }

    private NodeServiceBlockingStub authenticated(String ticket) {
        return stub.withInterceptors(ticketHeader(ticket)).withDeadlineAfter(deadline.toMillis(), TimeUnit.MILLISECONDS);
    }

    private static ClientInterceptor ticketHeader(String ticket) {
        val headers = new Metadata();
        headers.put(TICKET_HEADER, ticket);
        return MetadataUtils.newAttachHeadersInterceptor(headers);
    }
}

package org.saidone.protoalf.platform;

import com.google.protobuf.ByteString;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import lombok.val;
import org.alfresco.query.PagingRequest;
import org.alfresco.repo.security.authentication.AuthenticationUtil;
import org.alfresco.repo.security.permissions.AccessDeniedException;
import org.alfresco.service.ServiceRegistry;
import org.alfresco.service.cmr.repository.InvalidNodeRefException;
import org.alfresco.service.cmr.repository.NodeRef;
import org.saidone.protoalf.api.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Supplier;

public final class GrpcNodeService extends NodeServiceGrpc.NodeServiceImplBase {
    private static final Logger LOG = LoggerFactory.getLogger(GrpcNodeService.class);
    private final ServiceRegistry services;
    private final NodeMapper mapper;
    private final NodeWriter writer;
    private final ModelLister models;
    private final ContentFetcher contents;
    public GrpcNodeService(ServiceRegistry services) { this(services, null); }
    /** @param transforms synchronous transforms for text extraction, null when not available */
    public GrpcNodeService(ServiceRegistry services, org.alfresco.repo.rendition2.SynchronousTransformClient transforms) {
        this.services = services; this.mapper = new NodeMapper(services); this.writer = new NodeWriter(services); this.models = new ModelLister(services);
        this.contents = new ContentFetcher(services, transforms);
    }
    @Override public void getNode(GetNodeRequest request, StreamObserver<Node> observer) {
        execute(observer, () -> mapper.map(ref(request.getNodeRef())));
    }
    @Override public void listChildren(ListChildrenRequest request, StreamObserver<ListChildrenResponse> observer) {
        execute(observer, () -> {
            val size = pageSize(request.getSkipCount(), request.getMaxItems());
            val paging = new PagingRequest(request.getSkipCount(), size);
            // Same as the Alfresco REST API: ask for the exact (permission filtered) number of children.
            paging.setRequestTotalCountMax(Integer.MAX_VALUE);
            val page = services.getFileFolderService().list(ref(request.getNodeRef()), true, true, null,
                    null, java.util.List.of(new org.alfresco.util.Pair<>(org.alfresco.model.ContentModel.PROP_NAME, true)), paging);
            val total = page.getTotalResultCount();
            val response = ListChildrenResponse.newBuilder().setHasMoreItems(page.hasMoreItems())
                    .setTotalItems(total == null || total.getFirst() == null ? -1 : total.getFirst());
            for (val file : page.getPage()) response.addEntries(mapper.map(file.getNodeRef()));
            return response.build();
        });
    }
    @Override public void listModels(ListModelsRequest request, StreamObserver<ListModelsResponse> observer) {
        execute(observer, () -> models.list(request.getModel(), request.getIncludeProperties()));
    }
    @Override public void getContent(GetContentRequest request, StreamObserver<GetContentResponse> observer) {
        execute(observer, () -> contents.read(ref(request.getNodeRef()), request.getText(), request.getOffset(), request.getMaxBytes()));
    }
    @Override public void createNode(CreateNodeRequest request, StreamObserver<Node> observer) {
        write(observer, () -> mapper.map(writer.create(ref(request.getParentRef()), request.getName(), request.getType(),
                request.getPropertiesMap(), values(request.getPropertyValuesMap()), request.getAspectsList(),
                request.hasContent() ? request.getContent() : null)));
    }
    @Override public void updateNode(UpdateNodeRequest request, StreamObserver<Node> observer) {
        write(observer, () -> mapper.map(writer.update(ref(request.getNodeRef()), request.getName(), request.getType(),
                request.getPropertiesMap(), values(request.getPropertyValuesMap()),
                request.getAddAspectsList(), request.getRemoveAspectsList())));
    }
    /** The property_values of a request as name -> list of text values. */
    private static java.util.Map<String, List<String>> values(java.util.Map<String, PropertyValues> input) {
        java.util.Map<String, List<String>> result = new java.util.HashMap<>();
        input.forEach((name, values) -> result.put(name, values.getValuesList()));
        return result;
    }
    @Override public void updateContent(UpdateContentRequest request, StreamObserver<Node> observer) {
        write(observer, () -> mapper.map(writer.updateContent(ref(request.getNodeRef()), request.hasContent() ? request.getContent() : null)));
    }
    @Override public void moveNode(MoveNodeRequest request, StreamObserver<Node> observer) {
        write(observer, () -> mapper.map(writer.move(ref(request.getNodeRef()), ref(request.getTargetParentRef()), request.getName())));
    }
    @Override public void copyNode(CopyNodeRequest request, StreamObserver<Node> observer) {
        write(observer, () -> mapper.map(writer.copy(ref(request.getNodeRef()), ref(request.getTargetParentRef()), request.getName())));
    }
    @Override public void deleteNode(DeleteNodeRequest request, StreamObserver<DeleteNodeResponse> observer) {
        write(observer, () -> { writer.delete(ref(request.getNodeRef())); return DeleteNodeResponse.getDefaultInstance(); });
    }

    /**
     * Version history of a document, newest first as in Alfresco; empty when it is not versionable. version_ref is the
     * frozen node of each version: GetContent and DownloadContent accept it to read that version.
     */
    @Override public void listVersions(ListVersionsRequest request, StreamObserver<ListVersionsResponse> observer) {
        execute(observer, () -> {
            val response = ListVersionsResponse.newBuilder();
            val history = services.getVersionService().getVersionHistory(ref(request.getNodeRef()));
            if (history != null) for (val version : history.getAllVersions()) response.addVersions(version(version));
            return response.build();
        });
    }
    private VersionInfo version(org.alfresco.service.cmr.version.Version version) {
        val frozen = version.getFrozenStateNodeRef();
        val info = VersionInfo.newBuilder().setLabel(version.getVersionLabel()).setVersionRef(frozen.toString())
                .setMajor(version.getVersionType() == org.alfresco.service.cmr.version.VersionType.MAJOR)
                .setNode(mapper.mapVersion(frozen));
        if (version.getDescription() != null) info.setDescription(version.getDescription());
        if (version.getFrozenModifiedDate() != null) info.setModifiedMillis(version.getFrozenModifiedDate().getTime());
        if (version.getFrozenModifier() != null) info.setModifier(version.getFrozenModifier());
        return info.build();
    }

    /** Largest content UploadContent accepts, in bytes: larger uploads are refused with INVALID_ARGUMENT. */
    static final long MAX_UPLOAD_BYTES = 2L * 1024 * 1024 * 1024;
    /** Largest data message DownloadContent sends. */
    static final int DOWNLOAD_CHUNK_SIZE = 1024 * 1024;

    /** Streams the stored bytes of a document: one info message, then data messages of at most DOWNLOAD_CHUNK_SIZE. */
    @Override public void downloadContent(DownloadContentRequest request, StreamObserver<DownloadContentResponse> observer) {
        val user = TicketInterceptor.USER.get();
        if (user == null) { observer.onError(Status.UNAUTHENTICATED.asRuntimeException()); return; }
        ContentFetcher.ContentStream content;
        try {
            // Only the lookup is transactional: the bytes are streamed after it, so a retry cannot duplicate output.
            content = transact(user, true, () -> contents.open(ref(request.getNodeRef())));
        } catch (StatusRuntimeException e) { observer.onError(e); return; }
        stream(content, observer);
    }

    private void stream(ContentFetcher.ContentStream content, StreamObserver<DownloadContentResponse> observer) {
        try {
            observer.onNext(DownloadContentResponse.newBuilder().setInfo(content.info).build());
            if (content.reader != null) {
                try (InputStream in = content.reader.getContentInputStream()) {
                    val buffer = new byte[DOWNLOAD_CHUNK_SIZE];
                    for (int n = in.read(buffer); n != -1; n = in.read(buffer))
                        observer.onNext(DownloadContentResponse.newBuilder().setData(ByteString.copyFrom(buffer, 0, n)).build());
                }
            }
            observer.onCompleted();
        } catch (IOException | RuntimeException e) {
            if (Status.fromThrowable(e).getCode() == Status.Code.CANCELLED) { LOG.debug("Download cancelled by the client"); return; }
            LOG.error("Content download failed", e);
            observer.onError(Status.INTERNAL.withDescription("Content download failed").asRuntimeException());
        }
    }

    /** Creates a document from a stream of messages: the header first, then the data chunks. */
    @Override public StreamObserver<UploadContentRequest> uploadContent(StreamObserver<Node> observer) {
        return upload(observer, MAX_UPLOAD_BYTES);
    }

    /** @param maxBytes largest accepted content: MAX_UPLOAD_BYTES, lower in tests */
    StreamObserver<UploadContentRequest> upload(StreamObserver<Node> observer, long maxBytes) {
        val user = TicketInterceptor.USER.get();
        if (user == null) {
            observer.onError(Status.UNAUTHENTICATED.asRuntimeException());
            return new StreamObserver<>() {
                @Override public void onNext(UploadContentRequest value) { }
                @Override public void onError(Throwable t) { }
                @Override public void onCompleted() { }
            };
        }
        return new Upload(observer, user, maxBytes);
    }

    /**
     * Spools the data to a temporary file, then creates the document in one read-write transaction when the client
     * completes. The file is deleted however the call ends. Protocol violations are INVALID_ARGUMENT.
     */
    private final class Upload implements StreamObserver<UploadContentRequest> {
        private final StreamObserver<Node> observer;
        private final String user;
        private final long maxBytes;
        private CreateDocumentHeader header;
        private Path file;
        private OutputStream out;
        private long size;
        private boolean done;

        Upload(StreamObserver<Node> observer, String user, long maxBytes) {
            this.observer = observer;
            this.user = user;
            this.maxBytes = maxBytes;
        }

        @Override public void onNext(UploadContentRequest request) {
            if (done) return;
            try {
                if (header == null) {
                    if (request.getPartCase() != UploadContentRequest.PartCase.HEADER) {
                        fail(Status.INVALID_ARGUMENT.withDescription("The first message must be the header")); return;
                    }
                    header = request.getHeader();
                    file = Files.createTempFile("alfresco-upload-", ".tmp");
                    out = Files.newOutputStream(file);
                } else if (request.getPartCase() == UploadContentRequest.PartCase.HEADER) {
                    fail(Status.INVALID_ARGUMENT.withDescription("The header must be the first message only")); return;
                } else if (request.getPartCase() != UploadContentRequest.PartCase.DATA) {
                    fail(Status.INVALID_ARGUMENT.withDescription("Empty message: send data chunks after the header")); return;
                } else {
                    if (request.getData().size() > maxBytes - size) {
                        fail(Status.INVALID_ARGUMENT.withDescription(String.format("Content exceeds %d bytes", maxBytes))); return;
                    }
                    size += request.getData().size();
                    request.getData().writeTo(out);
                }
            } catch (IOException e) {
                LOG.error("Cannot spool the uploaded content", e);
                fail(Status.INTERNAL.withDescription("Upload failed"));
            }
        }

        @Override public void onCompleted() {
            if (done) return;
            if (header == null) { fail(Status.INVALID_ARGUMENT.withDescription("The header is required")); return; }
            done = true;
            Path spool = file;
            Status failure = null;
            Node node = null;
            try {
                out.close();
                node = transact(user, false, () -> mapper.map(writer.createDocument(ref(header.getParentRef()),
                        header.getName(), header.getType(), header.getPropertiesMap(),
                        values(header.getPropertyValuesMap()), header.getAspectsList(),
                        header.getMimetype(), header.getEncoding(), spool)));
            } catch (IOException e) {
                LOG.error("Cannot close the upload spool", e);
                failure = Status.INTERNAL.withDescription("Upload failed");
            } catch (StatusRuntimeException e) {
                failure = e.getStatus();
            } finally {
                cleanup();
            }
            if (failure != null) observer.onError(failure.asRuntimeException());
            else { observer.onNext(node); observer.onCompleted(); }
        }

        @Override public void onError(Throwable t) {
            // Cancelled or broken by the client: nothing to report back.
            if (done) return;
            done = true;
            LOG.debug("Upload ended by the client", t);
            cleanup();
        }

        private void fail(Status status) {
            done = true;
            cleanup();
            observer.onError(status.asRuntimeException());
        }

        /** Closes and deletes the spool file. Idempotent. */
        private void cleanup() {
            try {
                if (out != null) out.close();
            } catch (IOException e) {
                LOG.debug("Cannot close the upload spool", e);
            } finally { out = null; }
            try {
                if (file != null) Files.deleteIfExists(file);
            } catch (IOException e) {
                LOG.warn("Cannot delete the upload spool {}", file, e);
            } finally { file = null; }
        }
    }

    /** Accepted values of SearchRequest.language. */
    static final java.util.Map<String, String> LANGUAGES = java.util.Map.of(
            "afts", org.alfresco.service.cmr.search.SearchService.LANGUAGE_FTS_ALFRESCO,
            "cmis", org.alfresco.service.cmr.search.SearchService.LANGUAGE_CMIS_ALFRESCO);
    static final int MAX_QUERY_LENGTH = 4096;

    @Override public void search(SearchRequest request, StreamObserver<SearchResponse> observer) {
        execute(observer, () -> {
            val query = request.getQuery();
            if (query.isBlank() || query.length() > MAX_QUERY_LENGTH)
                throw new IllegalArgumentException(String.format("query must be 1..%d characters", MAX_QUERY_LENGTH));
            val languageName = request.getLanguage().isEmpty() ? "afts" : request.getLanguage();
            val language = LANGUAGES.get(languageName);
            if (language == null) throw new IllegalArgumentException("language must be afts or cmis");
            val size = pageSize(request.getSkipCount(), request.getMaxItems());
            val parameters = new org.alfresco.service.cmr.search.SearchParameters();
            parameters.addStore(org.alfresco.service.cmr.repository.StoreRef.STORE_REF_WORKSPACE_SPACESSTORE);
            parameters.setLanguage(language);
            parameters.setQuery(query);
            parameters.setSkipCount(request.getSkipCount());
            parameters.setMaxItems(size);
            sort(parameters, languageName, request.getSortList());
            org.alfresco.service.cmr.search.ResultSet results;
            try {
                results = services.getSearchService().query(parameters);
            } catch (org.alfresco.repo.search.impl.parsers.FTSQueryException
                     | org.alfresco.repo.search.impl.querymodel.QueryModelException
                     | org.alfresco.repo.search.QueryParserException e) {
                LOG.debug("Invalid search query: {}", query, e);
                throw new IllegalArgumentException(String.format("Invalid %s query: %s", languageName, rootMessage(e)));
            }
            try {
                val response = SearchResponse.newBuilder().setHasMoreItems(results.hasMore()).setTotalItems(results.getNumberFound());
                // The index can lag behind the repository: skip nodes deleted since they were indexed.
                for (val ref : results.getNodeRefs())
                    if (services.getNodeService().exists(ref)) response.addEntries(mapper.map(ref));
                return response.build();
            } finally {
                results.close();
            }
        });
    }
    static final int MAX_SORT_FIELDS = 10;
    /** Adds the sort criteria of SearchRequest.sort: properties must exist in the dictionary, "score" is the relevance. */
    private void sort(org.alfresco.service.cmr.search.SearchParameters parameters, String languageName, List<SortField> sort) {
        if (sort.isEmpty()) return;
        if (!"afts".equals(languageName)) throw new IllegalArgumentException("sort is supported for afts only: use ORDER BY in a cmis query");
        if (sort.size() > MAX_SORT_FIELDS) throw new IllegalArgumentException(String.format("At most %d sort fields", MAX_SORT_FIELDS));
        for (val field : sort) {
            val ascending = !field.getDescending();
            if ("score".equalsIgnoreCase(field.getField())) {
                parameters.addSort(new org.alfresco.service.cmr.search.SearchParameters.SortDefinition(
                        org.alfresco.service.cmr.search.SearchParameters.SortDefinition.SortType.SCORE, null, ascending));
                continue;
            }
            org.alfresco.service.namespace.QName property;
            try {
                property = org.alfresco.service.namespace.QName.resolveToQName(services.getNamespaceService(), field.getField());
            } catch (org.alfresco.service.namespace.NamespaceException | IllegalArgumentException e) {
                property = null;
            }
            if (property == null || services.getDictionaryService().getProperty(property) == null)
                throw new IllegalArgumentException(String.format("Invalid sort field %s: use a property (e.g. cm:modified or {uri}name) or score", field.getField()));
            parameters.addSort(String.format("@%s", property), ascending);
        }
    }
    private static String rootMessage(Throwable e) {
        while (e.getCause() != null && e.getCause() != e) e = e.getCause();
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }
    private static int pageSize(int skipCount, int maxItems) {
        val size = maxItems == 0 ? 100 : maxItems;
        if (size < 1 || size > 1000 || skipCount < 0 || skipCount > Integer.MAX_VALUE - size)
            throw new IllegalArgumentException("Invalid pagination: max_items must be 1..1000, skip_count >= 0");
        return size;
    }

    /** Node aliases accepted in place of a nodeRef, as in the Alfresco REST API. Resolved as the calling user. */
    static final java.util.Map<String, String> ALIASES = java.util.Map.of(
            "-root-", org.alfresco.repo.nodelocator.CompanyHomeNodeLocator.NAME,
            "-my-", org.alfresco.repo.nodelocator.UserHomeNodeLocator.NAME,
            "-shared-", org.alfresco.repo.nodelocator.SharedHomeNodeLocator.NAME);
    private static final java.util.regex.Pattern NODE_ID = java.util.regex.Pattern.compile("[A-Za-z0-9][A-Za-z0-9_.-]{0,99}");
    private static final String USAGE = "node_ref must be a nodeRef (workspace://SpacesStore/<id>), a node id or one of the aliases -root-, -my-, -shared-";

    /** Accepts a full nodeRef, a bare node id (workspace://SpacesStore) or an alias. Must run inside the transaction. */
    private NodeRef ref(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(USAGE);
        NodeRef ref;
        val locator = ALIASES.get(value);
        if (locator != null) {
            ref = services.getNodeLocatorService().getNode(locator, null, null);
            if (ref == null) throw Status.NOT_FOUND.withDescription(String.format("No node for alias %s", value)).asRuntimeException();
        } else if (NodeRef.isNodeRef(value)) {
            ref = new NodeRef(value);
        } else if (NODE_ID.matcher(value).matches()) {
            ref = new NodeRef(org.alfresco.service.cmr.repository.StoreRef.STORE_REF_WORKSPACE_SPACESSTORE, value);
        } else {
            throw new IllegalArgumentException(USAGE);
        }
        if (!services.getNodeService().exists(ref)) throw Status.NOT_FOUND.withDescription("Node not found").asRuntimeException();
        return ref;
    }
    private <T> void execute(StreamObserver<T> observer, Supplier<T> work) { run(observer, true, work); }
    private <T> void write(StreamObserver<T> observer, Supplier<T> work) { run(observer, false, work); }
    private <T> void run(StreamObserver<T> observer, boolean readOnly, Supplier<T> work) {
        val user = TicketInterceptor.USER.get();
        if (user == null) { observer.onError(Status.UNAUTHENTICATED.asRuntimeException()); return; }
        T result;
        try {
            result = transact(user, readOnly, work);
        } catch (StatusRuntimeException e) { observer.onError(e); return; }
        // Never emit a response inside a transaction that Alfresco may retry.
        observer.onNext(result); observer.onCompleted();
    }

    /** Runs the work in a transaction as the user. Failures are thrown as the gRPC status to report. */
    private <T> T transact(String user, boolean readOnly, Supplier<T> work) {
        try {
            return AuthenticationUtil.runAs(() -> services.getTransactionService().getRetryingTransactionHelper()
                    .doInTransaction(work::get, readOnly), user);
        } catch (AccessDeniedException e) { throw Status.PERMISSION_DENIED.asRuntimeException();
        } catch (InvalidNodeRefException e) { throw Status.NOT_FOUND.asRuntimeException();
        } catch (org.alfresco.service.cmr.model.FileExistsException | org.alfresco.service.cmr.repository.DuplicateChildNodeNameException e) {
            throw Status.ALREADY_EXISTS.withDescription("A node with the same name already exists in the target folder").asRuntimeException();
        } catch (org.alfresco.service.cmr.lock.NodeLockedException e) {
            throw Status.FAILED_PRECONDITION.withDescription("The node is locked").asRuntimeException();
        } catch (org.alfresco.service.cmr.repository.CyclicChildRelationshipException e) {
            throw Status.INVALID_ARGUMENT.withDescription("A folder cannot be moved or copied into itself").asRuntimeException();
        } catch (org.alfresco.repo.node.integrity.IntegrityException e) {
            LOG.debug("Integrity violation", e);
            throw Status.FAILED_PRECONDITION.withDescription(String.format("Model constraint violated: %s", rootMessage(e))).asRuntimeException();
        } catch (IllegalArgumentException e) { throw Status.INVALID_ARGUMENT.withDescription(e.getMessage()).asRuntimeException();
        } catch (StatusRuntimeException e) { throw e;
        } catch (RuntimeException e) {
            LOG.error("gRPC repository operation failed", e); throw Status.INTERNAL.withDescription("Repository operation failed").asRuntimeException();
        }
    }
}

package org.saidone.protoalf.platform;

import io.grpc.stub.StreamObserver;
import lombok.val;
import org.alfresco.model.ContentModel;
import org.alfresco.query.PagingRequest;
import org.alfresco.service.ServiceRegistry;
import org.alfresco.service.cmr.model.FileFolderService;
import org.alfresco.util.Pair;
import org.saidone.protoalf.api.*;

import java.util.List;

/**
 * Exposes file and folder listing, creation, moves and copies through gRPC.
 *
 * <p>Operations delegate to Alfresco's file/folder service and return mapped node snapshots. Listings include
 * files and folders ordered by name. Creation can additionally set properties, aspects and document content in
 * the same transaction. All operations preserve the calling user's repository permissions.
 *
 * <p>Requests accept the node reference forms described in the package documentation. Responses are emitted
 * only after the repository transaction completes; errors are delivered to the response observer.
 *
 * @see FileFolderService
 */
public final class GrpcFileFolderService extends FileFolderServiceGrpc.FileFolderServiceImplBase {

    /**
     * Repository services used under the caller's transaction and authentication context.
     */
    private final ServiceRegistry services;

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
     * Creates the adapter and its repository helpers without starting repository work.
     *
     * @param services Alfresco service registry; repository work uses its secured services
     */
    public GrpcFileFolderService(ServiceRegistry services) {
        this.services = services;
        this.support = new GrpcServiceSupport(services);
        this.mapper = new NodeMapper(services);
        this.writer = new NodeWriter(services);
    }

    /**
     * Lists files and folders directly under a parent, sorted by name. A zero page size selects 100; explicit
     * sizes must be 1..1000. The total is permission filtered, or -1 when unavailable.
     *
     * @param request parent node reference, non-negative skip count and optional page size
     * @param observer receives one page with entries, continuation flag and total count, or a gRPC error
     */
    @Override public void list(ListChildrenRequest request, StreamObserver<ListChildrenResponse> observer) {
        support.execute(observer, () -> {
            val size = GrpcServiceSupport.pageSize(request.getSkipCount(), request.getMaxItems());
            val paging = new PagingRequest(request.getSkipCount(), size);
            // Same as the Alfresco REST API: ask for the exact (permission filtered) number of children.
            paging.setRequestTotalCountMax(Integer.MAX_VALUE);
            val page = services.getFileFolderService().list(support.ref(request.getNodeRef()), true, true, null, null, List.of(new Pair<>(ContentModel.PROP_NAME, true)), paging);
            val total = page.getTotalResultCount();
            val response = ListChildrenResponse.newBuilder().setHasMoreItems(page.hasMoreItems()).setTotalItems(total == null || total.getFirst() == null ? -1 : total.getFirst());
            for (val file : page.getPage()) response.addEntries(mapper.map(file.getNodeRef()));
            return response.build();
        });
    }

    /**
     * Creates a document or folder with optional metadata and aspects. A blank type defaults to cm:content.
     * Content is optional for documents and forbidden for folders. Creation, metadata and content writes share
     * one transaction.
     *
     * @param request parent reference, name, type, properties, aspects and optional content
     * @param observer receives one snapshot of the newly created node, or a gRPC error
     */
    @Override public void create(CreateNodeRequest request, StreamObserver<Node> observer) {
        support.write(observer, () -> mapper.map(writer.create(support.ref(request.getParentRef()), request.getName(), request.getType(), request.getPropertiesMap(), GrpcServiceSupport.values(request.getPropertyValuesMap()), request.getAspectsList(), request.hasContent() ? request.getContent() : null)));
    }

    /**
     * Moves a node to another parent, optionally renaming it. A blank name preserves the current name.
     * Duplicate names, locks, permissions and hierarchy constraints are enforced by Alfresco.
     *
     * @param request source reference, destination parent reference and optional new name
     * @param observer receives one snapshot of the moved node, or a gRPC error
     */
    @Override public void move(MoveNodeRequest request, StreamObserver<Node> observer) {
        support.write(observer, () -> mapper.map(writer.move(support.ref(request.getNodeRef()), support.ref(request.getTargetParentRef()), request.getName())));
    }

    /**
     * Copies a node into a destination folder, optionally with a different name. Folder copy behavior is
     * delegated to Alfresco. A blank name preserves the original name.
     *
     * @param request source reference, destination parent reference and optional copied name
     * @param observer receives one snapshot of the new copy, or a gRPC error
     */
    @Override public void copy(CopyNodeRequest request, StreamObserver<Node> observer) {
        support.write(observer, () -> mapper.map(writer.copy(support.ref(request.getNodeRef()), support.ref(request.getTargetParentRef()), request.getName())));
    }
}

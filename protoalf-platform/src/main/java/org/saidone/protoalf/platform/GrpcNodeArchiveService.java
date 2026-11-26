package org.saidone.protoalf.platform;

import io.grpc.stub.StreamObserver;
import lombok.val;
import org.alfresco.query.PagingRequest;
import org.alfresco.repo.node.archive.ArchivedNodesCannedQueryBuilder;
import org.alfresco.repo.node.archive.NodeArchiveService;
import org.alfresco.service.ServiceRegistry;
import org.alfresco.service.cmr.repository.NodeRef;
import org.alfresco.service.cmr.repository.StoreRef;
import org.saidone.protoalf.api.*;

import java.util.ArrayList;

/**
 * Exposes the Alfresco trashcan (the archive store of workspace://SpacesStore) through gRPC.
 *
 * <p>Listing delegates to Alfresco's node archive service, which shows administrators every archived node and
 * other users only the nodes they deleted. Purging removes archived nodes permanently through the secured node
 * service, so the calling user's repository permissions apply. Archived nodes are addressed by their
 * archive://SpacesStore reference or by their bare node ID; references outside the archive store are rejected so
 * a live node can never be purged by mistake.
 *
 * <p>Reads run in read-only transactions and purges in retryable read-write transactions as the user
 * established by {@link TicketInterceptor}. Responses are emitted only after the transaction completes; errors
 * are delivered to the response observer.
 *
 * @see NodeArchiveService
 */
public final class GrpcNodeArchiveService extends NodeArchiveServiceGrpc.NodeArchiveServiceImplBase {

    /**
     * Alfresco archive service used for listing and purging archived nodes.
     */
    private final NodeArchiveService archive;

    /**
     * Shared caller-context, transaction, reference-resolution and error-mapping helper.
     */
    private final GrpcServiceSupport support;

    /**
     * Converter from repository state to transport-safe node snapshots.
     */
    private final NodeMapper mapper;

    /**
     * Creates the adapter and its repository helpers without starting repository work.
     *
     * @param services Alfresco service registry; repository work uses its secured services
     * @param archive Alfresco node archive service
     */
    public GrpcNodeArchiveService(ServiceRegistry services, NodeArchiveService archive) {
        this.archive = archive;
        this.support = new GrpcServiceSupport(services);
        this.mapper = new NodeMapper(services);
    }

    /**
     * Lists the archived nodes visible to the caller, most recently deleted first, optionally filtered by name.
     * A zero page size selects 100; explicit sizes must be 1..1000. The total is -1 when unavailable.
     *
     * @param request optional name filter, non-negative skip count and optional page size
     * @param observer receives one page with entries, continuation flag and total count, or a gRPC error
     */
    @Override public void listArchivedNodes(ListArchivedNodesRequest request, StreamObserver<ListArchivedNodesResponse> observer) {
        support.execute(observer, () -> {
            val size = GrpcServiceSupport.pageSize(request.getSkipCount(), request.getMaxItems());
            val paging = new PagingRequest(request.getSkipCount(), size);
            paging.setRequestTotalCountMax(Integer.MAX_VALUE);
            val page = archive.listArchivedNodes(query(request.getFilter(), paging));
            val total = page.getTotalResultCount();
            val response = ListArchivedNodesResponse.newBuilder().setHasMoreItems(page.hasMoreItems()).setTotalItems(total == null || total.getFirst() == null ? -1 : total.getFirst());
            for (val ref : page.getPage()) response.addEntries(mapper.map(ref));
            return response.build();
        });
    }

    /**
     * Permanently deletes one archived node and its archived children. The node cannot be restored afterwards.
     *
     * @param request archived node reference or bare node ID
     * @param observer receives one response reporting the purged node, or a gRPC error
     */
    @Override public void purgeArchivedNode(PurgeArchivedNodeRequest request, StreamObserver<PurgeArchivedNodesResponse> observer) {
        support.write(observer, () -> {
            archive.purgeArchivedNode(archived(request.getNodeRef()));
            return PurgeArchivedNodesResponse.newBuilder().setPurged(1).build();
        });
    }

    /**
     * Permanently deletes every archived node the caller can list, in one transaction. For an administrator this
     * empties the whole trashcan; other users purge only the nodes they deleted.
     *
     * @param request empty request
     * @param observer receives one response with the number of purged nodes, or a gRPC error
     */
    @Override public void purgeAllArchivedNodes(PurgeAllArchivedNodesRequest request, StreamObserver<PurgeArchivedNodesResponse> observer) {
        support.write(observer, () -> {
            // Collect first, then purge: deleting while paging would shift the pages.
            val refs = new ArrayList<NodeRef>();
            var skip = 0;
            while (true) {
                val page = archive.listArchivedNodes(query("", new PagingRequest(skip, 1000)));
                refs.addAll(page.getPage());
                if (!page.hasMoreItems() || page.getPage().isEmpty())
                    break;
                skip += page.getPage().size();
            }
            if (!refs.isEmpty())
                archive.purgeArchivedNodes(refs);
            return PurgeArchivedNodesResponse.newBuilder().setPurged(refs.size()).build();
        });
    }

    /**
     * Builds an archived-node query rooted at the workspace archive store, newest first.
     *
     * @param filter optional name filter; blank lists every visible node
     * @param paging requested page
     * @return the canned query parameters
     */
    private ArchivedNodesCannedQueryBuilder query(String filter, PagingRequest paging) {
        val builder = new ArchivedNodesCannedQueryBuilder.Builder(archive.getStoreArchiveNode(StoreRef.STORE_REF_WORKSPACE_SPACESSTORE), paging).sortOrderAscending(false);
        if (!filter.isBlank())
            builder.filter(filter);
        return builder.build();
    }

    /**
     * Resolves an archived node reference or bare node ID and verifies that it belongs to the workspace archive
     * store. Must run inside the caller's repository transaction.
     *
     * @param value archive://SpacesStore reference or bare node ID
     * @return the resolved existing archived node reference
     * @throws IllegalArgumentException if the value is blank, malformed or outside the archive store
     */
    private NodeRef archived(String value) {
        val store = archive.getStoreArchiveNode(StoreRef.STORE_REF_WORKSPACE_SPACESSTORE).getStoreRef();
        val ref = support.ref(value == null || value.isBlank() || NodeRef.isNodeRef(value) || GrpcServiceSupport.ALIASES.containsKey(value) ? value : new NodeRef(store, value).toString());
        if (!ref.getStoreRef().equals(store))
            throw new IllegalArgumentException(String.format("node_ref must be an archived node (%s://%s/<id>) or its id", store.getProtocol(), store.getIdentifier()));
        return ref;
    }
}

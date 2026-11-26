package org.saidone.protoalf.platform;

import io.grpc.Context;
import io.grpc.Status;
import lombok.val;
import org.alfresco.model.ContentModel;
import org.alfresco.query.PagingResults;
import org.alfresco.repo.node.archive.ArchivedNodesCannedQueryBuilder;
import org.alfresco.repo.node.archive.NodeArchiveService;
import org.alfresco.repo.security.authentication.AuthenticationUtil;
import org.alfresco.repo.transaction.RetryingTransactionHelper;
import org.alfresco.service.ServiceRegistry;
import org.alfresco.service.cmr.repository.NodeRef;
import org.alfresco.service.cmr.repository.NodeService;
import org.alfresco.service.cmr.repository.StoreRef;
import org.alfresco.service.transaction.TransactionService;
import org.alfresco.util.Pair;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.saidone.protoalf.api.*;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class GrpcNodeArchiveServiceTest {

    private static final StoreRef ARCHIVE = new StoreRef("archive://SpacesStore");

    private static final NodeRef ARCHIVE_ROOT = new NodeRef(ARCHIVE, "archive-root");

    @BeforeAll
    static void initializeAuthentication() throws Exception {
        val util = new AuthenticationUtil();
        util.setDefaultAdminUserName("admin");
        util.setDefaultGuestUserName("guest");
        util.afterPropertiesSet();
    }

    @AfterEach
    void clearAuthentication() {
        AuthenticationUtil.clearCurrentSecurityContext();
    }

    /**
     * Repository mocks: transactions run the callback and every node exists.
     */
    private static ServiceRegistry repository() {
        val services = mock(ServiceRegistry.class);
        val tx = mock(TransactionService.class);
        val helper = mock(RetryingTransactionHelper.class);
        val nodes = mock(NodeService.class);
        when(services.getTransactionService()).thenReturn(tx);
        when(tx.getRetryingTransactionHelper()).thenReturn(helper);
        when(helper.doInTransaction(any(RetryingTransactionHelper.RetryingTransactionCallback.class), anyBoolean())).thenAnswer(invocation -> ((RetryingTransactionHelper.RetryingTransactionCallback<?>) invocation.getArgument(0)).execute());
        when(services.getNodeService()).thenReturn(nodes);
        when(nodes.exists(any(NodeRef.class))).thenReturn(true);
        when(nodes.getType(any(NodeRef.class))).thenReturn(ContentModel.TYPE_CONTENT);
        when(nodes.getProperties(any(NodeRef.class))).thenReturn(Map.of());
        when(nodes.getAspects(any(NodeRef.class))).thenReturn(Set.of());
        return services;
    }

    private static NodeArchiveService archive() {
        val archive = mock(NodeArchiveService.class);
        when(archive.getStoreArchiveNode(StoreRef.STORE_REF_WORKSPACE_SPACESSTORE)).thenReturn(ARCHIVE_ROOT);
        return archive;
    }

    @SuppressWarnings("unchecked")
    private static PagingResults<NodeRef> page(List<NodeRef> refs, boolean more) {
        PagingResults<NodeRef> page = mock(PagingResults.class);
        when(page.getPage()).thenReturn(refs);
        when(page.hasMoreItems()).thenReturn(more);
        when(page.getTotalResultCount()).thenReturn(new Pair<>(refs.size(), refs.size()));
        return page;
    }

    private static List<NodeRef> archived(int from, int count) {
        return IntStream.range(from, from + count).mapToObj(i -> new NodeRef(ARCHIVE, "node-" + i)).collect(Collectors.toList());
    }

    @Test
    void listsArchivedNodesNewestFirstWithNameFilter() {
        val archive = archive();
        val page = page(archived(0, 2), false);
        when(archive.listArchivedNodes(any())).thenReturn(page);
        val result = new GrpcRepositoryServiceTest.Result<ListArchivedNodesResponse>();
        Context.current().withValue(TicketInterceptor.USER, "admin").run(() -> new GrpcNodeArchiveService(repository(), archive).listArchivedNodes(ListArchivedNodesRequest.newBuilder().setFilter("report").setMaxItems(10).build(), result));
        assertNull(result.error);
        assertEquals(2, result.value.getEntriesCount());
        assertEquals("archive://SpacesStore/node-0", result.value.getEntries(0).getNodeRef());
        assertEquals(2, result.value.getTotalItems());
        val query = ArgumentCaptor.forClass(ArchivedNodesCannedQueryBuilder.class);
        verify(archive).listArchivedNodes(query.capture());
        assertEquals(ARCHIVE_ROOT, query.getValue().getArchiveRootNodeRef());
        assertEquals("report", query.getValue().getFilter());
        assertFalse(query.getValue().getSortOrderAscending());
        assertEquals(10, query.getValue().getPagingRequest().getMaxItems());
    }

    @Test
    void purgesAnArchivedNodeByBareId() {
        val archive = archive();
        val result = new GrpcRepositoryServiceTest.Result<PurgeArchivedNodesResponse>();
        Context.current().withValue(TicketInterceptor.USER, "admin").run(() -> new GrpcNodeArchiveService(repository(), archive).purgeArchivedNode(PurgeArchivedNodeRequest.newBuilder().setNodeRef("node-1").build(), result));
        assertNull(result.error);
        assertEquals(1, result.value.getPurged());
        verify(archive).purgeArchivedNode(new NodeRef(ARCHIVE, "node-1"));
    }

    @Test
    void refusesToPurgeALiveNode() {
        val archive = archive();
        val result = new GrpcRepositoryServiceTest.Result<PurgeArchivedNodesResponse>();
        Context.current().withValue(TicketInterceptor.USER, "admin").run(() -> new GrpcNodeArchiveService(repository(), archive).purgeArchivedNode(PurgeArchivedNodeRequest.newBuilder().setNodeRef("workspace://SpacesStore/node-1").build(), result));
        assertEquals(Status.Code.INVALID_ARGUMENT, Status.fromThrowable(result.error).getCode());
        verify(archive, never()).purgeArchivedNode(any());
    }

    @Test
    void purgeAllCollectsEveryPageBeforePurging() {
        val archive = archive();
        val first = page(archived(0, 1000), true);
        val second = page(archived(1000, 5), false);
        when(archive.listArchivedNodes(any())).thenReturn(first, second);
        val result = new GrpcRepositoryServiceTest.Result<PurgeArchivedNodesResponse>();
        Context.current().withValue(TicketInterceptor.USER, "admin").run(() -> new GrpcNodeArchiveService(repository(), archive).purgeAllArchivedNodes(PurgeAllArchivedNodesRequest.getDefaultInstance(), result));
        assertNull(result.error);
        assertEquals(1005, result.value.getPurged());
        val query = ArgumentCaptor.forClass(ArchivedNodesCannedQueryBuilder.class);
        verify(archive, times(2)).listArchivedNodes(query.capture());
        assertEquals(1000, query.getAllValues().get(1).getPagingRequest().getSkipCount());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<NodeRef>> purged = ArgumentCaptor.forClass(List.class);
        verify(archive).purgeArchivedNodes(purged.capture());
        assertEquals(1005, purged.getValue().size());
    }

    @Test
    void emptyTrashcanPurgesNothing() {
        val archive = archive();
        val page = page(List.of(), false);
        when(archive.listArchivedNodes(any())).thenReturn(page);
        val result = new GrpcRepositoryServiceTest.Result<PurgeArchivedNodesResponse>();
        Context.current().withValue(TicketInterceptor.USER, "admin").run(() -> new GrpcNodeArchiveService(repository(), archive).purgeAllArchivedNodes(PurgeAllArchivedNodesRequest.getDefaultInstance(), result));
        assertNull(result.error);
        assertEquals(0, result.value.getPurged());
        verify(archive, never()).purgeArchivedNodes(any());
    }
}

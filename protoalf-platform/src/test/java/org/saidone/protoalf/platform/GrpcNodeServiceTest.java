package org.saidone.protoalf.platform;

import com.google.protobuf.ByteString;
import io.grpc.*;
import io.grpc.stub.StreamObserver;
import lombok.val;
import org.alfresco.model.ContentModel;
import org.alfresco.query.PagingRequest;
import org.alfresco.query.PagingResults;
import org.alfresco.repo.nodelocator.NodeLocatorService;
import org.alfresco.repo.search.impl.parsers.FTSQueryException;
import org.alfresco.repo.security.authentication.AuthenticationUtil;
import org.alfresco.repo.security.permissions.AccessDeniedException;
import org.alfresco.repo.transaction.RetryingTransactionHelper;
import org.alfresco.service.ServiceRegistry;
import org.alfresco.service.cmr.dictionary.DictionaryService;
import org.alfresco.service.cmr.dictionary.PropertyDefinition;
import org.alfresco.service.cmr.lock.NodeLockedException;
import org.alfresco.service.cmr.model.FileExistsException;
import org.alfresco.service.cmr.model.FileFolderService;
import org.alfresco.service.cmr.repository.ChildAssociationRef;
import org.alfresco.service.cmr.repository.ContentData;
import org.alfresco.service.cmr.repository.NodeRef;
import org.alfresco.service.cmr.repository.NodeService;
import org.alfresco.service.cmr.search.ResultSet;
import org.alfresco.service.cmr.search.SearchParameters;
import org.alfresco.service.cmr.search.SearchService;
import org.alfresco.service.cmr.security.AccessStatus;
import org.alfresco.service.cmr.security.PermissionService;
import org.alfresco.service.namespace.NamespaceService;
import org.alfresco.service.namespace.QName;
import org.alfresco.service.transaction.TransactionService;
import org.alfresco.util.Pair;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.saidone.protoalf.api.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class GrpcNodeServiceTest {

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

    static class Result<T> implements StreamObserver<T> {

        T value;

        Throwable error;

        boolean complete;

        public void onNext(T v) {
            value = v;
        }

        public void onError(Throwable t) {
            error = t;
        }

        public void onCompleted() {
            complete = true;
        }
    }

    @Test
    void anonymousRequestNeverTouchesRepository() {
        val services = mock(ServiceRegistry.class);
        val result = new Result<Node>();
        new GrpcNodeService(services).getNode(GetNodeRequest.getDefaultInstance(), result);
        assertEquals(Status.Code.UNAUTHENTICATED, Status.fromThrowable(result.error).getCode());
        verify(services, never()).getTransactionService();
    }

    @Test
    void permissionFailureDoesNotExposeDetailsOrSendNode() {
        val services = mock(ServiceRegistry.class);
        val tx = mock(TransactionService.class);
        val helper = mock(RetryingTransactionHelper.class);
        when(services.getTransactionService()).thenReturn(tx);
        when(tx.getRetryingTransactionHelper()).thenReturn(helper);
        when(helper.doInTransaction(any(RetryingTransactionHelper.RetryingTransactionCallback.class), eq(true))).thenAnswer(invocation -> {
            assertEquals("reader", AuthenticationUtil.getRunAsUser());
            throw new AccessDeniedException("private internal detail");
        });
        val result = new Result<Node>();
        Context.current().withValue(TicketInterceptor.USER, "reader").run(() -> new GrpcNodeService(services).getNode(GetNodeRequest.newBuilder().setNodeRef("workspace://SpacesStore/00000000-0000-0000-0000-000000000001").build(), result));
        assertEquals(Status.Code.PERMISSION_DENIED, Status.fromThrowable(result.error).getCode());
        assertNull(Status.fromThrowable(result.error).getDescription());
        assertNull(result.value);
        assertFalse(result.complete);
        assertNull(AuthenticationUtil.getFullyAuthenticatedUser());
    }

    @Test
    void contentMetadataNeverExposesContentStoreUrl() {
        val content = new ContentData("store://secret/location", "application/pdf", 42, "UTF-8");
        val value = NodeMapper.value(content);
        assertEquals(42, value.getContentValue().getSize());
        assertFalse(value.toString().contains("secret"));
    }

    @Test
    void propertyTypesArePreserved() {
        assertEquals(123L, NodeMapper.value(123L).getIntegerValue());
        assertEquals(true, NodeMapper.value(true).getBooleanValue());
        assertEquals(123L, NodeMapper.value(new Date(123)).getTimestampMillis());
        assertEquals("{urn:test}x", NodeMapper.value(QName.createQName("urn:test", "x")).getQnameValue());
    }

    private static final NodeRef COMPANY_HOME = new NodeRef("workspace://SpacesStore/company-home");

    /**
     * Repository mocks: transactions run the callback, every node exists unless listed as missing.
     */
    private static ServiceRegistry repository(NodeLocatorService locator) {
        val services = mock(ServiceRegistry.class);
        val tx = mock(TransactionService.class);
        val helper = mock(RetryingTransactionHelper.class);
        val nodes = mock(NodeService.class);
        when(services.getTransactionService()).thenReturn(tx);
        when(tx.getRetryingTransactionHelper()).thenReturn(helper);
        when(helper.doInTransaction(any(RetryingTransactionHelper.RetryingTransactionCallback.class), anyBoolean())).thenAnswer(invocation -> ((RetryingTransactionHelper.RetryingTransactionCallback<?>) invocation.getArgument(0)).execute());
        when(services.getNodeService()).thenReturn(nodes);
        when(services.getNodeLocatorService()).thenReturn(locator);
        when(nodes.exists(any(NodeRef.class))).thenReturn(true);
        when(nodes.getType(any(NodeRef.class))).thenReturn(ContentModel.TYPE_FOLDER);
        when(nodes.getProperties(any(NodeRef.class))).thenReturn(Map.of());
        when(nodes.getAspects(any(NodeRef.class))).thenReturn(Set.of());
        return services;
    }

    private static Result<Node> getNode(ServiceRegistry services, String nodeRef) {
        val result = new Result<Node>();
        Context.current().withValue(TicketInterceptor.USER, "reader").run(() -> new GrpcNodeService(services).getNode(GetNodeRequest.newBuilder().setNodeRef(nodeRef).build(), result));
        return result;
    }

    @Test
    void aliasesAreResolvedAsTheCallingUser() {
        val locator = mock(NodeLocatorService.class);
        when(locator.getNode(eq("companyhome"), isNull(), isNull())).thenAnswer(invocation -> {
            assertEquals("reader", AuthenticationUtil.getRunAsUser());
            return COMPANY_HOME;
        });
        val result = getNode(repository(locator), "-root-");
        assertNull(result.error);
        assertEquals(COMPANY_HOME.toString(), result.value.getNodeRef());
    }

    @Test
    void everyAliasMapsToANodeLocator() {
        assertEquals(Map.of("-root-", "companyhome", "-my-", "userhome", "-shared-", "sharedhome"), GrpcNodeService.ALIASES);
    }

    @Test
    void aliasWithoutANodeIsNotFound() {
        val locator = mock(NodeLocatorService.class);
        val result = getNode(repository(locator), "-my-");
        assertEquals(Status.Code.NOT_FOUND, Status.fromThrowable(result.error).getCode());
        assertEquals("No node for alias -my-", Status.fromThrowable(result.error).getDescription());
    }

    @Test
    void bareNodeIdDefaultsToSpacesStore() {
        val result = getNode(repository(mock(NodeLocatorService.class)), "6188c572-ca86-4827-88c5-72ca86982759");
        assertNull(result.error);
        assertEquals("workspace://SpacesStore/6188c572-ca86-4827-88c5-72ca86982759", result.value.getNodeRef());
    }

    @Test
    void unknownAliasesAndMalformedValuesAreRejected() {
        val services = repository(mock(NodeLocatorService.class));
        for (val value : new String[] { "", "-unknown-", "../etc", "a b" }) {
            val status = Status.fromThrowable(getNode(services, value).error);
            assertEquals(Status.Code.INVALID_ARGUMENT, status.getCode(), value);
            assertTrue(status.getDescription().contains("-root-, -my-, -shared-"), value);
        }
    }

    private static Result<SearchResponse> search(ServiceRegistry services, SearchRequest request) {
        val result = new Result<SearchResponse>();
        Context.current().withValue(TicketInterceptor.USER, "reader").run(() -> new GrpcNodeService(services).search(request, result));
        return result;
    }

    @Test
    void searchRunsTheQueryAndSkipsNodesDeletedSinceIndexing() {
        val services = repository(mock(NodeLocatorService.class));
        val searchService = mock(SearchService.class);
        val results = mock(ResultSet.class);
        val live = new NodeRef("workspace://SpacesStore/live");
        val deleted = new NodeRef("workspace://SpacesStore/deleted");
        when(services.getSearchService()).thenReturn(searchService);
        when(searchService.query(any(SearchParameters.class))).thenReturn(results);
        when(results.getNodeRefs()).thenReturn(List.of(live, deleted));
        when(results.hasMore()).thenReturn(true);
        when(results.getNumberFound()).thenReturn(42L);
        when(services.getNodeService().exists(deleted)).thenReturn(false);
        val result = search(services, SearchRequest.newBuilder().setQuery("cm:name:budget*").setSkipCount(10).setMaxItems(5).build());
        assertNull(result.error);
        assertEquals(List.of(live.toString()), result.value.getEntriesList().stream().map(Node::getNodeRef).collect(Collectors.toList()));
        assertTrue(result.value.getHasMoreItems());
        assertEquals(42L, result.value.getTotalItems());
        val captor = ArgumentCaptor.forClass(SearchParameters.class);
        verify(searchService).query(captor.capture());
        assertEquals("fts-alfresco", captor.getValue().getLanguage());
        assertEquals("cm:name:budget*", captor.getValue().getQuery());
        assertEquals(10, captor.getValue().getSkipCount());
        assertEquals(5, captor.getValue().getMaxItems());
        verify(results).close();
    }

    @Test
    void invalidSearchRequestsAreRejectedBeforeQuerying() {
        val services = repository(mock(NodeLocatorService.class));
        val searchService = mock(SearchService.class);
        when(services.getSearchService()).thenReturn(searchService);
        for (val request : List.of(SearchRequest.newBuilder().setQuery(" ").build(), SearchRequest.newBuilder().setQuery("x".repeat(GrpcNodeService.MAX_QUERY_LENGTH + 1)).build(), SearchRequest.newBuilder().setQuery("x").setLanguage("lucene").build(), SearchRequest.newBuilder().setQuery("x").setMaxItems(1001).build())) {
            assertEquals(Status.Code.INVALID_ARGUMENT, Status.fromThrowable(search(services, request).error).getCode(), request.toString());
        }
        verifyNoInteractions(searchService);
    }

    @Test
    void queryParseErrorsAreReportedAsInvalidArgument() {
        val services = repository(mock(NodeLocatorService.class));
        val searchService = mock(SearchService.class);
        when(services.getSearchService()).thenReturn(searchService);
        when(searchService.query(any(SearchParameters.class))).thenThrow(new FTSQueryException("bad token"));
        val status = Status.fromThrowable(search(services, SearchRequest.newBuilder().setQuery("cm:name:(").build()).error);
        assertEquals(Status.Code.INVALID_ARGUMENT, status.getCode());
        assertTrue(status.getDescription().startsWith("Invalid afts query: "), status.getDescription());
    }

    private static ServiceRegistry searchable(SearchService searchService) {
        val services = repository(mock(NodeLocatorService.class));
        val namespaces = mock(NamespaceService.class);
        val dictionary = mock(DictionaryService.class);
        val results = mock(ResultSet.class);
        when(services.getSearchService()).thenReturn(searchService);
        when(services.getNamespaceService()).thenReturn(namespaces);
        when(services.getDictionaryService()).thenReturn(dictionary);
        when(namespaces.getNamespaceURI("cm")).thenReturn(NamespaceService.CONTENT_MODEL_1_0_URI);
        when(dictionary.getProperty(ContentModel.PROP_MODIFIED)).thenReturn(mock(PropertyDefinition.class));
        when(searchService.query(any(SearchParameters.class))).thenReturn(results);
        when(results.getNodeRefs()).thenReturn(List.of());
        return services;
    }

    @Test
    void sortFieldsArePassedToTheSearchInOrder() {
        val searchService = mock(SearchService.class);
        val request = SearchRequest.newBuilder().setQuery("x").addSort(SortField.newBuilder().setField("cm:modified").setDescending(true)).addSort(SortField.newBuilder().setField("score")).build();
        assertNull(search(searchable(searchService), request).error);
        val captor = ArgumentCaptor.forClass(SearchParameters.class);
        verify(searchService).query(captor.capture());
        val sort = captor.getValue().getSortDefinitions();
        assertEquals(2, sort.size());
        assertEquals("@{http://www.alfresco.org/model/content/1.0}modified", sort.get(0).getField());
        assertFalse(sort.get(0).isAscending());
        assertEquals(SearchParameters.SortDefinition.SortType.SCORE, sort.get(1).getSortType());
        assertTrue(sort.get(1).isAscending());
    }

    @Test
    void invalidSortIsRejectedBeforeQuerying() {
        val searchService = mock(SearchService.class);
        val services = searchable(searchService);
        val tooMany = SearchRequest.newBuilder().setQuery("x");
        for (int i = 0; i <= GrpcNodeService.MAX_SORT_FIELDS; i++) tooMany.addSort(SortField.newBuilder().setField("cm:modified"));
        for (val request : List.of(SearchRequest.newBuilder().setQuery("x").addSort(SortField.newBuilder().setField("cm:unknown")).build(), SearchRequest.newBuilder().setQuery("x").addSort(SortField.newBuilder().setField("zz:modified")).build(), SearchRequest.newBuilder().setQuery("x").addSort(SortField.newBuilder().setField("")).build(), SearchRequest.newBuilder().setQuery("SELECT * FROM cmis:document").setLanguage("cmis").addSort(SortField.newBuilder().setField("cm:modified")).build(), tooMany.build())) {
            assertEquals(Status.Code.INVALID_ARGUMENT, Status.fromThrowable(search(services, request).error).getCode(), request.toString());
        }
        verifyNoInteractions(searchService);
    }

    @Test
    void nodeCarriesItsParentOnlyWhenTheCallerCanReadIt() {
        val services = repository(mock(NodeLocatorService.class));
        val permissions = mock(PermissionService.class);
        when(services.getPermissionService()).thenReturn(permissions);
        val readable = new NodeRef("workspace://SpacesStore/readable");
        val hidden = new NodeRef("workspace://SpacesStore/hidden");
        val child = new NodeRef("workspace://SpacesStore/child");
        val orphan = new NodeRef("workspace://SpacesStore/orphan");
        val nodes = services.getNodeService();
        when(nodes.getPrimaryParent(child)).thenReturn(new ChildAssociationRef(ContentModel.ASSOC_CONTAINS, readable, ContentModel.ASSOC_CONTAINS, child));
        when(nodes.getPrimaryParent(orphan)).thenReturn(new ChildAssociationRef(ContentModel.ASSOC_CONTAINS, hidden, ContentModel.ASSOC_CONTAINS, orphan));
        when(permissions.hasPermission(readable, PermissionService.READ_PROPERTIES)).thenReturn(AccessStatus.ALLOWED);
        when(permissions.hasPermission(hidden, PermissionService.READ_PROPERTIES)).thenReturn(AccessStatus.DENIED);
        assertEquals(readable.toString(), getNode(services, child.toString()).value.getParentRef());
        assertEquals("", getNode(services, orphan.toString()).value.getParentRef());
        assertEquals("", getNode(services, "workspace://SpacesStore/root").value.getParentRef());
    }

    @SuppressWarnings("unchecked")
    @Test
    void listChildrenReportsTheExactTotalLikeTheRestApi() {
        val services = repository(mock(NodeLocatorService.class));
        val files = mock(FileFolderService.class);
        val page = mock(PagingResults.class);
        when(services.getFileFolderService()).thenReturn(files);
        when(files.list(any(NodeRef.class), eq(true), eq(true), isNull(), isNull(), anyList(), any(PagingRequest.class))).thenReturn(page);
        when(page.getPage()).thenReturn(List.of());
        when(page.hasMoreItems()).thenReturn(false);
        when(page.getTotalResultCount()).thenReturn(new Pair<>(37, 37));
        val result = new Result<ListChildrenResponse>();
        Context.current().withValue(TicketInterceptor.USER, "reader").run(() -> new GrpcNodeService(services).listChildren(ListChildrenRequest.newBuilder().setNodeRef(COMPANY_HOME.toString()).setSkipCount(40).build(), result));
        assertNull(result.error);
        assertEquals(37L, result.value.getTotalItems());
        assertFalse(result.value.getHasMoreItems());
        val captor = ArgumentCaptor.forClass(PagingRequest.class);
        verify(files).list(any(NodeRef.class), eq(true), eq(true), isNull(), isNull(), anyList(), captor.capture());
        assertEquals(Integer.MAX_VALUE, captor.getValue().getRequestTotalCountMax());
        assertEquals(40, captor.getValue().getSkipCount());
    }

    @SuppressWarnings("unchecked")
    @Test
    void unknownChildCountIsMinusOne() {
        val services = repository(mock(NodeLocatorService.class));
        val files = mock(FileFolderService.class);
        val page = mock(PagingResults.class);
        when(services.getFileFolderService()).thenReturn(files);
        when(files.list(any(NodeRef.class), eq(true), eq(true), isNull(), isNull(), anyList(), any(PagingRequest.class))).thenReturn(page);
        when(page.getPage()).thenReturn(List.of());
        val result = new Result<ListChildrenResponse>();
        Context.current().withValue(TicketInterceptor.USER, "reader").run(() -> new GrpcNodeService(services).listChildren(ListChildrenRequest.newBuilder().setNodeRef(COMPANY_HOME.toString()).build(), result));
        assertNull(result.error);
        assertEquals(-1L, result.value.getTotalItems());
    }

    private static RetryingTransactionHelper transactions(ServiceRegistry services) {
        return services.getTransactionService().getRetryingTransactionHelper();
    }

    @Test
    void writesRunInAReadWriteTransactionAndReadsInAReadOnlyOne() {
        val services = repository(mock(NodeLocatorService.class));
        val result = new Result<DeleteNodeResponse>();
        Context.current().withValue(TicketInterceptor.USER, "reader").run(() -> new GrpcNodeService(services).deleteNode(DeleteNodeRequest.newBuilder().setNodeRef("workspace://SpacesStore/doc").build(), result));
        assertNull(result.error);
        assertTrue(result.complete);
        verify(transactions(services)).doInTransaction(any(RetryingTransactionHelper.RetryingTransactionCallback.class), eq(false));
        verify(services.getNodeService()).deleteNode(new NodeRef("workspace://SpacesStore/doc"));
        getNode(services, "workspace://SpacesStore/doc");
        verify(transactions(services)).doInTransaction(any(RetryingTransactionHelper.RetryingTransactionCallback.class), eq(true));
    }

    @Test
    void duplicateNamesAreReportedAsAlreadyExists() throws Exception {
        val services = repository(mock(NodeLocatorService.class));
        val files = mock(FileFolderService.class);
        when(services.getFileFolderService()).thenReturn(files);
        when(files.copy(any(NodeRef.class), any(NodeRef.class), isNull())).thenThrow(new FileExistsException(COMPANY_HOME, "doc"));
        val result = new Result<Node>();
        Context.current().withValue(TicketInterceptor.USER, "reader").run(() -> new GrpcNodeService(services).copyNode(CopyNodeRequest.newBuilder().setNodeRef("doc").setTargetParentRef("folder").build(), result));
        val status = Status.fromThrowable(result.error);
        assertEquals(Status.Code.ALREADY_EXISTS, status.getCode());
        assertNull(result.value);
    }

    @Test
    void lockedNodesAreReportedAsFailedPrecondition() {
        val services = repository(mock(NodeLocatorService.class));
        val locked = mock(NodeLockedException.class);
        val nodes = services.getNodeService();
        doThrow(locked).when(nodes).deleteNode(any(NodeRef.class));
        val result = new Result<DeleteNodeResponse>();
        Context.current().withValue(TicketInterceptor.USER, "reader").run(() -> new GrpcNodeService(services).deleteNode(DeleteNodeRequest.newBuilder().setNodeRef("doc").build(), result));
        assertEquals(Status.Code.FAILED_PRECONDITION, Status.fromThrowable(result.error).getCode());
    }

    @Test
    void anonymousDownloadNeverTouchesRepository() {
        val services = mock(ServiceRegistry.class);
        val result = new Result<DownloadContentResponse>();
        new GrpcNodeService(services).downloadContent(DownloadContentRequest.getDefaultInstance(), result);
        assertEquals(Status.Code.UNAUTHENTICATED, Status.fromThrowable(result.error).getCode());
        verify(services, never()).getTransactionService();
    }

    @Test
    void anonymousUploadNeverTouchesRepository() {
        val services = mock(ServiceRegistry.class);
        val result = new Result<Node>();
        new GrpcNodeService(services).uploadContent(result);
        assertEquals(Status.Code.UNAUTHENTICATED, Status.fromThrowable(result.error).getCode());
        verify(services, never()).getTransactionService();
    }

    private static final UploadContentRequest HEADER = UploadContentRequest.newBuilder().setHeader(CreateDocumentHeader.newBuilder().setParentRef("workspace://SpacesStore/parent").setName("notes.txt")).build();

    private static UploadContentRequest data(String text) {
        return UploadContentRequest.newBuilder().setData(ByteString.copyFromUtf8(text)).build();
    }

    private static long uploadSpools() throws IOException {
        try (val files = Files.list(Path.of(System.getProperty("java.io.tmpdir")))) {
            return files.filter(p -> p.getFileName().toString().startsWith("alfresco-upload-")).count();
        }
    }

    @Test
    void uploadMustStartWithTheHeader() throws Exception {
        val services = mock(ServiceRegistry.class);
        val result = new Result<Node>();
        val before = uploadSpools();
        Context.current().withValue(TicketInterceptor.USER, "reader").run(() -> new GrpcNodeService(services).uploadContent(result).onNext(data("no header")));
        assertEquals(Status.Code.INVALID_ARGUMENT, Status.fromThrowable(result.error).getCode());
        assertFalse(result.complete);
        assertEquals(before, uploadSpools());
        verify(services, never()).getTransactionService();
    }

    @Test
    void uploadRejectsAHeaderOrAnEmptyMessageAfterTheFirst() throws Exception {
        val services = mock(ServiceRegistry.class);
        val before = uploadSpools();
        for (val later : new UploadContentRequest[] { HEADER, UploadContentRequest.getDefaultInstance() }) {
            val result = new Result<Node>();
            Context.current().withValue(TicketInterceptor.USER, "reader").run(() -> {
                val upload = new GrpcNodeService(services).uploadContent(result);
                upload.onNext(HEADER);
                upload.onNext(later);
                upload.onNext(data("ignored"));
                upload.onCompleted();
            });
            assertEquals(Status.Code.INVALID_ARGUMENT, Status.fromThrowable(result.error).getCode());
            assertFalse(result.complete);
        }
        assertEquals(before, uploadSpools());
        verify(services, never()).getTransactionService();
    }

    @Test
    void uploadAboveTheSizeLimitIsRejectedAndSpoolDeleted() throws Exception {
        val services = mock(ServiceRegistry.class);
        val result = new Result<Node>();
        val before = uploadSpools();
        Context.current().withValue(TicketInterceptor.USER, "reader").run(() -> {
            val upload = new GrpcNodeService(services).upload(result, 4);
            upload.onNext(HEADER);
            upload.onNext(data("1234"));
            upload.onNext(data("5"));
            upload.onNext(data("6"));
            upload.onCompleted();
        });
        assertEquals(Status.Code.INVALID_ARGUMENT, Status.fromThrowable(result.error).getCode());
        assertFalse(result.complete);
        assertEquals(before, uploadSpools());
        verify(services, never()).getTransactionService();
    }

    @Test
    void uploadMaxSizeIsTwoGiB() {
        assertEquals(2L * 1024 * 1024 * 1024, GrpcNodeService.MAX_UPLOAD_BYTES);
    }
}

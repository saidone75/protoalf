package org.saidone.protoalf.platform;

import io.grpc.*;
import io.grpc.stub.StreamObserver;
import org.alfresco.service.ServiceRegistry;
import org.alfresco.service.cmr.repository.NodeRef;
import org.alfresco.repo.security.permissions.AccessDeniedException;
import org.alfresco.service.transaction.TransactionService;
import org.alfresco.repo.transaction.RetryingTransactionHelper;
import org.junit.jupiter.api.Test;
import org.saidone.protoalf.api.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class GrpcNodeServiceTest {
    @org.junit.jupiter.api.BeforeAll static void initializeAuthentication() throws Exception {
        var util = new org.alfresco.repo.security.authentication.AuthenticationUtil();
        util.setDefaultAdminUserName("admin");
        util.setDefaultGuestUserName("guest");
        util.afterPropertiesSet();
    }
    @org.junit.jupiter.api.AfterEach void clearAuthentication() {
        org.alfresco.repo.security.authentication.AuthenticationUtil.clearCurrentSecurityContext();
    }
    static class Result<T> implements StreamObserver<T> {
        T value; Throwable error; boolean complete;
        public void onNext(T v) { value=v; }
        public void onError(Throwable t) { error=t; }
        public void onCompleted() { complete=true; }
    }
    @Test void anonymousRequestNeverTouchesRepository() {
        var services=mock(ServiceRegistry.class);
        var result=new Result<Node>();
        new GrpcNodeService(services).getNode(GetNodeRequest.getDefaultInstance(),result);
        assertEquals(Status.Code.UNAUTHENTICATED,Status.fromThrowable(result.error).getCode());
        verify(services,never()).getTransactionService();
    }
    @Test void permissionFailureDoesNotExposeDetailsOrSendNode() {
        var services=mock(ServiceRegistry.class);
        var tx=mock(TransactionService.class);
        var helper=mock(RetryingTransactionHelper.class);
        when(services.getTransactionService()).thenReturn(tx);
        when(tx.getRetryingTransactionHelper()).thenReturn(helper);
        when(helper.doInTransaction(any(RetryingTransactionHelper.RetryingTransactionCallback.class),eq(true)))
                .thenAnswer(invocation -> {
                    assertEquals("reader", org.alfresco.repo.security.authentication.AuthenticationUtil.getRunAsUser());
                    throw new AccessDeniedException("private internal detail");
                });
        var result=new Result<Node>();
        Context.current().withValue(TicketInterceptor.USER,"reader").run(() ->
                new GrpcNodeService(services).getNode(GetNodeRequest.newBuilder()
                        .setNodeRef("workspace://SpacesStore/00000000-0000-0000-0000-000000000001").build(),result));
        assertEquals(Status.Code.PERMISSION_DENIED,Status.fromThrowable(result.error).getCode());
        assertNull(Status.fromThrowable(result.error).getDescription());
        assertNull(result.value); assertFalse(result.complete);
        assertNull(org.alfresco.repo.security.authentication.AuthenticationUtil.getFullyAuthenticatedUser());
    }
    @Test void contentMetadataNeverExposesContentStoreUrl() {
        var content=new org.alfresco.service.cmr.repository.ContentData("store://secret/location","application/pdf",42,"UTF-8");
        var value=NodeMapper.value(content);
        assertEquals(42,value.getContentValue().getSize());
        assertFalse(value.toString().contains("secret"));
    }
    @Test void propertyTypesArePreserved() {
        assertEquals(123L,NodeMapper.value(123L).getIntegerValue());
        assertEquals(true,NodeMapper.value(true).getBooleanValue());
        assertEquals(123L,NodeMapper.value(new java.util.Date(123)).getTimestampMillis());
        assertEquals("{urn:test}x",NodeMapper.value(org.alfresco.service.namespace.QName.createQName("urn:test","x")).getQnameValue());
    }

    private static final NodeRef COMPANY_HOME = new NodeRef("workspace://SpacesStore/company-home");

    /** Repository mocks: transactions run the callback, every node exists unless listed as missing. */
    private static ServiceRegistry repository(org.alfresco.repo.nodelocator.NodeLocatorService locator) {
        var services=mock(ServiceRegistry.class);
        var tx=mock(TransactionService.class);
        var helper=mock(RetryingTransactionHelper.class);
        var nodes=mock(org.alfresco.service.cmr.repository.NodeService.class);
        when(services.getTransactionService()).thenReturn(tx);
        when(tx.getRetryingTransactionHelper()).thenReturn(helper);
        when(helper.doInTransaction(any(RetryingTransactionHelper.RetryingTransactionCallback.class),anyBoolean()))
                .thenAnswer(invocation -> ((RetryingTransactionHelper.RetryingTransactionCallback<?>)invocation.getArgument(0)).execute());
        when(services.getNodeService()).thenReturn(nodes);
        when(services.getNodeLocatorService()).thenReturn(locator);
        when(nodes.exists(any(NodeRef.class))).thenReturn(true);
        when(nodes.getType(any(NodeRef.class))).thenReturn(org.alfresco.model.ContentModel.TYPE_FOLDER);
        when(nodes.getProperties(any(NodeRef.class))).thenReturn(java.util.Map.of());
        when(nodes.getAspects(any(NodeRef.class))).thenReturn(java.util.Set.of());
        return services;
    }
    private static Result<Node> getNode(ServiceRegistry services, String nodeRef) {
        var result=new Result<Node>();
        Context.current().withValue(TicketInterceptor.USER,"reader").run(() ->
                new GrpcNodeService(services).getNode(GetNodeRequest.newBuilder().setNodeRef(nodeRef).build(),result));
        return result;
    }
    @Test void aliasesAreResolvedAsTheCallingUser() {
        var locator=mock(org.alfresco.repo.nodelocator.NodeLocatorService.class);
        when(locator.getNode(eq("companyhome"),isNull(),isNull())).thenAnswer(invocation -> {
            assertEquals("reader", org.alfresco.repo.security.authentication.AuthenticationUtil.getRunAsUser());
            return COMPANY_HOME;
        });
        var result=getNode(repository(locator),"-root-");
        assertNull(result.error);
        assertEquals(COMPANY_HOME.toString(),result.value.getNodeRef());
    }
    @Test void everyAliasMapsToANodeLocator() {
        assertEquals(java.util.Map.of("-root-","companyhome","-my-","userhome","-shared-","sharedhome"),GrpcNodeService.ALIASES);
    }
    @Test void aliasWithoutANodeIsNotFound() {
        var locator=mock(org.alfresco.repo.nodelocator.NodeLocatorService.class);
        var result=getNode(repository(locator),"-my-");
        assertEquals(Status.Code.NOT_FOUND,Status.fromThrowable(result.error).getCode());
        assertEquals("No node for alias -my-",Status.fromThrowable(result.error).getDescription());
    }
    @Test void bareNodeIdDefaultsToSpacesStore() {
        var result=getNode(repository(mock(org.alfresco.repo.nodelocator.NodeLocatorService.class)),"6188c572-ca86-4827-88c5-72ca86982759");
        assertNull(result.error);
        assertEquals("workspace://SpacesStore/6188c572-ca86-4827-88c5-72ca86982759",result.value.getNodeRef());
    }
    @Test void unknownAliasesAndMalformedValuesAreRejected() {
        var services=repository(mock(org.alfresco.repo.nodelocator.NodeLocatorService.class));
        for (String value : new String[]{"","-unknown-","../etc","a b"}) {
            var status=Status.fromThrowable(getNode(services,value).error);
            assertEquals(Status.Code.INVALID_ARGUMENT,status.getCode(),value);
            assertTrue(status.getDescription().contains("-root-, -my-, -shared-"),value);
        }
    }

    private static Result<SearchResponse> search(ServiceRegistry services, SearchRequest request) {
        var result=new Result<SearchResponse>();
        Context.current().withValue(TicketInterceptor.USER,"reader").run(() -> new GrpcNodeService(services).search(request,result));
        return result;
    }
    @Test void searchRunsTheQueryAndSkipsNodesDeletedSinceIndexing() {
        var services=repository(mock(org.alfresco.repo.nodelocator.NodeLocatorService.class));
        var searchService=mock(org.alfresco.service.cmr.search.SearchService.class);
        var results=mock(org.alfresco.service.cmr.search.ResultSet.class);
        var live=new NodeRef("workspace://SpacesStore/live");
        var deleted=new NodeRef("workspace://SpacesStore/deleted");
        when(services.getSearchService()).thenReturn(searchService);
        when(searchService.query(any(org.alfresco.service.cmr.search.SearchParameters.class))).thenReturn(results);
        when(results.getNodeRefs()).thenReturn(java.util.List.of(live,deleted));
        when(results.hasMore()).thenReturn(true);
        when(results.getNumberFound()).thenReturn(42L);
        when(services.getNodeService().exists(deleted)).thenReturn(false);

        var result=search(services,SearchRequest.newBuilder().setQuery("cm:name:budget*").setSkipCount(10).setMaxItems(5).build());

        assertNull(result.error);
        assertEquals(java.util.List.of(live.toString()),result.value.getEntriesList().stream().map(Node::getNodeRef).collect(java.util.stream.Collectors.toList()));
        assertTrue(result.value.getHasMoreItems());
        assertEquals(42L,result.value.getTotalItems());
        var captor=org.mockito.ArgumentCaptor.forClass(org.alfresco.service.cmr.search.SearchParameters.class);
        verify(searchService).query(captor.capture());
        assertEquals("fts-alfresco",captor.getValue().getLanguage());
        assertEquals("cm:name:budget*",captor.getValue().getQuery());
        assertEquals(10,captor.getValue().getSkipCount());
        assertEquals(5,captor.getValue().getMaxItems());
        verify(results).close();
    }
    @Test void invalidSearchRequestsAreRejectedBeforeQuerying() {
        var services=repository(mock(org.alfresco.repo.nodelocator.NodeLocatorService.class));
        var searchService=mock(org.alfresco.service.cmr.search.SearchService.class);
        when(services.getSearchService()).thenReturn(searchService);
        for (var request : java.util.List.of(
                SearchRequest.newBuilder().setQuery(" ").build(),
                SearchRequest.newBuilder().setQuery("x".repeat(GrpcNodeService.MAX_QUERY_LENGTH+1)).build(),
                SearchRequest.newBuilder().setQuery("x").setLanguage("lucene").build(),
                SearchRequest.newBuilder().setQuery("x").setMaxItems(1001).build())) {
            assertEquals(Status.Code.INVALID_ARGUMENT,Status.fromThrowable(search(services,request).error).getCode(),request.toString());
        }
        verifyNoInteractions(searchService);
    }
    @Test void queryParseErrorsAreReportedAsInvalidArgument() {
        var services=repository(mock(org.alfresco.repo.nodelocator.NodeLocatorService.class));
        var searchService=mock(org.alfresco.service.cmr.search.SearchService.class);
        when(services.getSearchService()).thenReturn(searchService);
        when(searchService.query(any(org.alfresco.service.cmr.search.SearchParameters.class)))
                .thenThrow(new org.alfresco.repo.search.impl.parsers.FTSQueryException("bad token"));
        var status=Status.fromThrowable(search(services,SearchRequest.newBuilder().setQuery("cm:name:(").build()).error);
        assertEquals(Status.Code.INVALID_ARGUMENT,status.getCode());
        assertTrue(status.getDescription().startsWith("Invalid afts query: "),status.getDescription());
    }
    private static ServiceRegistry searchable(org.alfresco.service.cmr.search.SearchService searchService) {
        var services=repository(mock(org.alfresco.repo.nodelocator.NodeLocatorService.class));
        var namespaces=mock(org.alfresco.service.namespace.NamespaceService.class);
        var dictionary=mock(org.alfresco.service.cmr.dictionary.DictionaryService.class);
        var results=mock(org.alfresco.service.cmr.search.ResultSet.class);
        when(services.getSearchService()).thenReturn(searchService);
        when(services.getNamespaceService()).thenReturn(namespaces);
        when(services.getDictionaryService()).thenReturn(dictionary);
        when(namespaces.getNamespaceURI("cm")).thenReturn(org.alfresco.service.namespace.NamespaceService.CONTENT_MODEL_1_0_URI);
        when(dictionary.getProperty(org.alfresco.model.ContentModel.PROP_MODIFIED))
                .thenReturn(mock(org.alfresco.service.cmr.dictionary.PropertyDefinition.class));
        when(searchService.query(any(org.alfresco.service.cmr.search.SearchParameters.class))).thenReturn(results);
        when(results.getNodeRefs()).thenReturn(java.util.List.of());
        return services;
    }
    @Test void sortFieldsArePassedToTheSearchInOrder() {
        var searchService=mock(org.alfresco.service.cmr.search.SearchService.class);
        var request=SearchRequest.newBuilder().setQuery("x")
                .addSort(SortField.newBuilder().setField("cm:modified").setDescending(true))
                .addSort(SortField.newBuilder().setField("score")).build();

        assertNull(search(searchable(searchService),request).error);

        var captor=org.mockito.ArgumentCaptor.forClass(org.alfresco.service.cmr.search.SearchParameters.class);
        verify(searchService).query(captor.capture());
        var sort=captor.getValue().getSortDefinitions();
        assertEquals(2,sort.size());
        assertEquals("@{http://www.alfresco.org/model/content/1.0}modified",sort.get(0).getField());
        assertFalse(sort.get(0).isAscending());
        assertEquals(org.alfresco.service.cmr.search.SearchParameters.SortDefinition.SortType.SCORE,sort.get(1).getSortType());
        assertTrue(sort.get(1).isAscending());
    }
    @Test void invalidSortIsRejectedBeforeQuerying() {
        var searchService=mock(org.alfresco.service.cmr.search.SearchService.class);
        var services=searchable(searchService);
        var tooMany=SearchRequest.newBuilder().setQuery("x");
        for (int i=0;i<=GrpcNodeService.MAX_SORT_FIELDS;i++) tooMany.addSort(SortField.newBuilder().setField("cm:modified"));
        for (var request : java.util.List.of(
                SearchRequest.newBuilder().setQuery("x").addSort(SortField.newBuilder().setField("cm:unknown")).build(),
                SearchRequest.newBuilder().setQuery("x").addSort(SortField.newBuilder().setField("zz:modified")).build(),
                SearchRequest.newBuilder().setQuery("x").addSort(SortField.newBuilder().setField("")).build(),
                SearchRequest.newBuilder().setQuery("SELECT * FROM cmis:document").setLanguage("cmis")
                        .addSort(SortField.newBuilder().setField("cm:modified")).build(),
                tooMany.build())) {
            assertEquals(Status.Code.INVALID_ARGUMENT,Status.fromThrowable(search(services,request).error).getCode(),request.toString());
        }
        verifyNoInteractions(searchService);
    }
    @Test void nodeCarriesItsParentOnlyWhenTheCallerCanReadIt() {
        var services=repository(mock(org.alfresco.repo.nodelocator.NodeLocatorService.class));
        var permissions=mock(org.alfresco.service.cmr.security.PermissionService.class);
        when(services.getPermissionService()).thenReturn(permissions);
        var readable=new NodeRef("workspace://SpacesStore/readable");
        var hidden=new NodeRef("workspace://SpacesStore/hidden");
        var child=new NodeRef("workspace://SpacesStore/child");
        var orphan=new NodeRef("workspace://SpacesStore/orphan");
        var nodes=services.getNodeService();
        when(nodes.getPrimaryParent(child)).thenReturn(new org.alfresco.service.cmr.repository.ChildAssociationRef(
                org.alfresco.model.ContentModel.ASSOC_CONTAINS,readable,org.alfresco.model.ContentModel.ASSOC_CONTAINS,child));
        when(nodes.getPrimaryParent(orphan)).thenReturn(new org.alfresco.service.cmr.repository.ChildAssociationRef(
                org.alfresco.model.ContentModel.ASSOC_CONTAINS,hidden,org.alfresco.model.ContentModel.ASSOC_CONTAINS,orphan));
        when(permissions.hasPermission(readable,org.alfresco.service.cmr.security.PermissionService.READ_PROPERTIES))
                .thenReturn(org.alfresco.service.cmr.security.AccessStatus.ALLOWED);
        when(permissions.hasPermission(hidden,org.alfresco.service.cmr.security.PermissionService.READ_PROPERTIES))
                .thenReturn(org.alfresco.service.cmr.security.AccessStatus.DENIED);

        assertEquals(readable.toString(),getNode(services,child.toString()).value.getParentRef());
        assertEquals("",getNode(services,orphan.toString()).value.getParentRef());
        assertEquals("",getNode(services,"workspace://SpacesStore/root").value.getParentRef());
    }

    @SuppressWarnings("unchecked")
    @Test void listChildrenReportsTheExactTotalLikeTheRestApi() {
        var services=repository(mock(org.alfresco.repo.nodelocator.NodeLocatorService.class));
        var files=mock(org.alfresco.service.cmr.model.FileFolderService.class);
        var page=mock(org.alfresco.query.PagingResults.class);
        when(services.getFileFolderService()).thenReturn(files);
        when(files.list(any(NodeRef.class),eq(true),eq(true),isNull(),isNull(),anyList(),any(org.alfresco.query.PagingRequest.class))).thenReturn(page);
        when(page.getPage()).thenReturn(java.util.List.of());
        when(page.hasMoreItems()).thenReturn(false);
        when(page.getTotalResultCount()).thenReturn(new org.alfresco.util.Pair<>(37,37));
        var result=new Result<ListChildrenResponse>();
        Context.current().withValue(TicketInterceptor.USER,"reader").run(() -> new GrpcNodeService(services).listChildren(
                ListChildrenRequest.newBuilder().setNodeRef(COMPANY_HOME.toString()).setSkipCount(40).build(),result));

        assertNull(result.error);
        assertEquals(37L,result.value.getTotalItems());
        assertFalse(result.value.getHasMoreItems());
        var captor=org.mockito.ArgumentCaptor.forClass(org.alfresco.query.PagingRequest.class);
        verify(files).list(any(NodeRef.class),eq(true),eq(true),isNull(),isNull(),anyList(),captor.capture());
        assertEquals(Integer.MAX_VALUE,captor.getValue().getRequestTotalCountMax());
        assertEquals(40,captor.getValue().getSkipCount());
    }
    @SuppressWarnings("unchecked")
    @Test void unknownChildCountIsMinusOne() {
        var services=repository(mock(org.alfresco.repo.nodelocator.NodeLocatorService.class));
        var files=mock(org.alfresco.service.cmr.model.FileFolderService.class);
        var page=mock(org.alfresco.query.PagingResults.class);
        when(services.getFileFolderService()).thenReturn(files);
        when(files.list(any(NodeRef.class),eq(true),eq(true),isNull(),isNull(),anyList(),any(org.alfresco.query.PagingRequest.class))).thenReturn(page);
        when(page.getPage()).thenReturn(java.util.List.of());
        var result=new Result<ListChildrenResponse>();
        Context.current().withValue(TicketInterceptor.USER,"reader").run(() -> new GrpcNodeService(services).listChildren(
                ListChildrenRequest.newBuilder().setNodeRef(COMPANY_HOME.toString()).build(),result));
        assertNull(result.error);
        assertEquals(-1L,result.value.getTotalItems());
    }

    private static RetryingTransactionHelper transactions(ServiceRegistry services) {
        return services.getTransactionService().getRetryingTransactionHelper();
    }
    @Test void writesRunInAReadWriteTransactionAndReadsInAReadOnlyOne() {
        var services=repository(mock(org.alfresco.repo.nodelocator.NodeLocatorService.class));
        var result=new Result<DeleteNodeResponse>();
        Context.current().withValue(TicketInterceptor.USER,"reader").run(() -> new GrpcNodeService(services).deleteNode(
                DeleteNodeRequest.newBuilder().setNodeRef("workspace://SpacesStore/doc").build(),result));
        assertNull(result.error);
        assertTrue(result.complete);
        verify(transactions(services)).doInTransaction(any(RetryingTransactionHelper.RetryingTransactionCallback.class),eq(false));
        verify(services.getNodeService()).deleteNode(new NodeRef("workspace://SpacesStore/doc"));

        getNode(services,"workspace://SpacesStore/doc");
        verify(transactions(services)).doInTransaction(any(RetryingTransactionHelper.RetryingTransactionCallback.class),eq(true));
    }
    @Test void duplicateNamesAreReportedAsAlreadyExists() throws Exception {
        var services=repository(mock(org.alfresco.repo.nodelocator.NodeLocatorService.class));
        var files=mock(org.alfresco.service.cmr.model.FileFolderService.class);
        when(services.getFileFolderService()).thenReturn(files);
        when(files.copy(any(NodeRef.class),any(NodeRef.class),isNull()))
                .thenThrow(new org.alfresco.service.cmr.model.FileExistsException(COMPANY_HOME,"doc"));
        var result=new Result<Node>();
        Context.current().withValue(TicketInterceptor.USER,"reader").run(() -> new GrpcNodeService(services).copyNode(
                CopyNodeRequest.newBuilder().setNodeRef("doc").setTargetParentRef("folder").build(),result));
        var status=Status.fromThrowable(result.error);
        assertEquals(Status.Code.ALREADY_EXISTS,status.getCode());
        assertNull(result.value);
    }
    @Test void lockedNodesAreReportedAsFailedPrecondition() {
        var services=repository(mock(org.alfresco.repo.nodelocator.NodeLocatorService.class));
        var locked=mock(org.alfresco.service.cmr.lock.NodeLockedException.class);
        var nodes=services.getNodeService();
        doThrow(locked).when(nodes).deleteNode(any(NodeRef.class));
        var result=new Result<DeleteNodeResponse>();
        Context.current().withValue(TicketInterceptor.USER,"reader").run(() -> new GrpcNodeService(services).deleteNode(
                DeleteNodeRequest.newBuilder().setNodeRef("doc").build(),result));
        assertEquals(Status.Code.FAILED_PRECONDITION,Status.fromThrowable(result.error).getCode());
    }

    @Test void anonymousDownloadNeverTouchesRepository() {
        var services=mock(ServiceRegistry.class);
        var result=new Result<DownloadContentResponse>();
        new GrpcNodeService(services).downloadContent(DownloadContentRequest.getDefaultInstance(),result);
        assertEquals(Status.Code.UNAUTHENTICATED,Status.fromThrowable(result.error).getCode());
        verify(services,never()).getTransactionService();
    }
    @Test void anonymousUploadNeverTouchesRepository() {
        var services=mock(ServiceRegistry.class);
        var result=new Result<Node>();
        new GrpcNodeService(services).uploadContent(result);
        assertEquals(Status.Code.UNAUTHENTICATED,Status.fromThrowable(result.error).getCode());
        verify(services,never()).getTransactionService();
    }
    private static final UploadContentRequest HEADER=UploadContentRequest.newBuilder().setHeader(CreateDocumentHeader.newBuilder()
            .setParentRef("workspace://SpacesStore/parent").setName("notes.txt")).build();
    private static UploadContentRequest data(String text) {
        return UploadContentRequest.newBuilder().setData(com.google.protobuf.ByteString.copyFromUtf8(text)).build();
    }
    private static long uploadSpools() throws java.io.IOException {
        try (var files=java.nio.file.Files.list(java.nio.file.Path.of(System.getProperty("java.io.tmpdir")))) {
            return files.filter(p -> p.getFileName().toString().startsWith("alfresco-upload-")).count();
        }
    }
    @Test void uploadMustStartWithTheHeader() throws Exception {
        var services=mock(ServiceRegistry.class);
        var result=new Result<Node>();
        long before=uploadSpools();
        Context.current().withValue(TicketInterceptor.USER,"reader").run(() ->
                new GrpcNodeService(services).uploadContent(result).onNext(data("no header")));
        assertEquals(Status.Code.INVALID_ARGUMENT,Status.fromThrowable(result.error).getCode());
        assertFalse(result.complete);
        assertEquals(before,uploadSpools());
        verify(services,never()).getTransactionService();
    }
    @Test void uploadRejectsAHeaderOrAnEmptyMessageAfterTheFirst() throws Exception {
        var services=mock(ServiceRegistry.class);
        long before=uploadSpools();
        for (UploadContentRequest later : new UploadContentRequest[]{HEADER, UploadContentRequest.getDefaultInstance()}) {
            var result=new Result<Node>();
            Context.current().withValue(TicketInterceptor.USER,"reader").run(() -> {
                var upload=new GrpcNodeService(services).uploadContent(result);
                upload.onNext(HEADER);
                upload.onNext(later);
                upload.onNext(data("ignored"));
                upload.onCompleted();
            });
            assertEquals(Status.Code.INVALID_ARGUMENT,Status.fromThrowable(result.error).getCode());
            assertFalse(result.complete);
        }
        assertEquals(before,uploadSpools());
        verify(services,never()).getTransactionService();
    }
    @Test void uploadAboveTheSizeLimitIsRejectedAndSpoolDeleted() throws Exception {
        var services=mock(ServiceRegistry.class);
        var result=new Result<Node>();
        long before=uploadSpools();
        Context.current().withValue(TicketInterceptor.USER,"reader").run(() -> {
            var upload=new GrpcNodeService(services).upload(result,4);
            upload.onNext(HEADER);
            upload.onNext(data("1234"));
            upload.onNext(data("5"));
            upload.onNext(data("6"));
            upload.onCompleted();
        });
        assertEquals(Status.Code.INVALID_ARGUMENT,Status.fromThrowable(result.error).getCode());
        assertFalse(result.complete);
        assertEquals(before,uploadSpools());
        verify(services,never()).getTransactionService();
    }
    @Test void uploadMaxSizeIsTwoGiB() {
        assertEquals(2L*1024*1024*1024,GrpcNodeService.MAX_UPLOAD_BYTES);
    }
}

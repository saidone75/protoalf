package org.saidone.protoalf.platform;

import io.grpc.stub.StreamObserver;
import lombok.extern.slf4j.Slf4j;
import lombok.val;
import org.alfresco.repo.search.QueryParserException;
import org.alfresco.repo.search.impl.parsers.FTSQueryException;
import org.alfresco.repo.search.impl.querymodel.QueryModelException;
import org.alfresco.service.ServiceRegistry;
import org.alfresco.service.cmr.repository.StoreRef;
import org.alfresco.service.cmr.search.ResultSet;
import org.alfresco.service.cmr.search.SearchParameters;
import org.alfresco.service.cmr.search.SearchService;
import org.alfresco.service.namespace.NamespaceException;
import org.alfresco.service.namespace.QName;
import org.saidone.protoalf.api.*;

import java.util.List;
import java.util.Map;

/** Adapts Alfresco SearchService.query with query language, pagination and ordered sort parameters. */
@Slf4j
public final class GrpcSearchService extends SearchServiceGrpc.SearchServiceImplBase {

    private final ServiceRegistry services;
    private final GrpcServiceSupport support;
    private final NodeMapper mapper;

    public GrpcSearchService(ServiceRegistry services) {
        this.services = services;
        this.support = new GrpcServiceSupport(services);
        this.mapper = new NodeMapper(services);
    }

    /**
     * Accepted values of SearchRequest.language.
     */
    static final Map<String, String> LANGUAGES = Map.of("afts", SearchService.LANGUAGE_FTS_ALFRESCO, "cmis", SearchService.LANGUAGE_CMIS_ALFRESCO);
    static final int MAX_QUERY_LENGTH = 4096;
    static final int MAX_SORT_FIELDS = 10;

    @Override public void query(SearchRequest request, StreamObserver<SearchResponse> observer) {
        support.execute(observer, () -> {
            val query = request.getQuery();
            if (query.isBlank() || query.length() > MAX_QUERY_LENGTH)
                throw new IllegalArgumentException(String.format("query must be 1..%d characters", MAX_QUERY_LENGTH));
            val languageName = request.getLanguage().isEmpty() ? "afts" : request.getLanguage();
            val language = LANGUAGES.get(languageName);
            if (language == null)
                throw new IllegalArgumentException("language must be afts or cmis");
            val size = GrpcServiceSupport.pageSize(request.getSkipCount(), request.getMaxItems());
            val parameters = new SearchParameters();
            parameters.addStore(StoreRef.STORE_REF_WORKSPACE_SPACESSTORE);
            parameters.setLanguage(language);
            parameters.setQuery(query);
            parameters.setSkipCount(request.getSkipCount());
            parameters.setMaxItems(size);
            sort(parameters, languageName, request.getSortList());
            ResultSet results;
            try {
                results = services.getSearchService().query(parameters);
            } catch (FTSQueryException | QueryModelException | QueryParserException e) {
                log.debug("Invalid search query: {}", query, e);
                throw new IllegalArgumentException(String.format("Invalid %s query: %s", languageName, GrpcServiceSupport.rootMessage(e)));
            }
            try {
                val response = SearchResponse.newBuilder().setHasMoreItems(results.hasMore()).setTotalItems(results.getNumberFound());
                // The index can lag behind the repository: skip nodes deleted since they were indexed.
                for (val ref : results.getNodeRefs()) if (services.getNodeService().exists(ref))
                    response.addEntries(mapper.map(ref));
                return response.build();
            } finally {
                results.close();
            }
        });
    }

    /**
     * Adds the sort criteria of SearchRequest.sort: properties must exist in the dictionary, "score" is the relevance.
     */
    private void sort(SearchParameters parameters, String languageName, List<SortField> sort) {
        if (sort.isEmpty())
            return;
        if (!"afts".equals(languageName))
            throw new IllegalArgumentException("sort is supported for afts only: use ORDER BY in a cmis query");
        if (sort.size() > MAX_SORT_FIELDS)
            throw new IllegalArgumentException(String.format("At most %d sort fields", MAX_SORT_FIELDS));
        for (val field : sort) {
            val ascending = !field.getDescending();
            if ("score".equalsIgnoreCase(field.getField())) {
                parameters.addSort(new SearchParameters.SortDefinition(SearchParameters.SortDefinition.SortType.SCORE, null, ascending));
                continue;
            }
            QName property;
            try {
                property = QName.resolveToQName(services.getNamespaceService(), field.getField());
            } catch (NamespaceException | IllegalArgumentException e) {
                property = null;
            }
            if (property == null || services.getDictionaryService().getProperty(property) == null)
                throw new IllegalArgumentException(String.format("Invalid sort field %s: use a property (e.g. cm:modified or {uri}name) or score", field.getField()));
            parameters.addSort(String.format("@%s", property), ascending);
        }
    }
}

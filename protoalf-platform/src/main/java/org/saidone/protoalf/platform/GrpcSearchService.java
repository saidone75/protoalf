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

/**
 * Adapts Alfresco's search service to paged, permission-filtered gRPC queries.
 *
 * <p>Queries target the workspace store and support AFTS and CMIS SQL. AFTS accepts an ordered list of property
 * or relevance sort fields; CMIS sorting belongs in the query's {@code ORDER BY} clause. Search results reflect
 * the index and may lag repository writes. Nodes deleted since indexing are omitted from the returned entries.
 *
 * <p>Queries run in a read-only transaction as the authenticated caller. The backing result set is always
 * closed, including when node mapping fails.
 *
 * @see SearchService
 */
@Slf4j
public final class GrpcSearchService extends SearchServiceGrpc.SearchServiceImplBase {

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
     * Creates the adapter and its repository helpers without starting repository work.
     *
     * @param services Alfresco service registry; repository work uses its secured services
     */
    public GrpcSearchService(ServiceRegistry services) {
        this.services = services;
        this.support = new GrpcServiceSupport(services);
        this.mapper = new NodeMapper(services);
    }

    /**
     * Remote language identifiers mapped to Alfresco search language constants.
     */
    static final Map<String, String> LANGUAGES = Map.of("afts", SearchService.LANGUAGE_FTS_ALFRESCO, "cmis", SearchService.LANGUAGE_CMIS_ALFRESCO);

    /**
     * Maximum accepted query length in Java characters.
     */
    static final int MAX_QUERY_LENGTH = 4096;

    /**
     * Maximum number of explicit AFTS sort fields accepted per request.
     */
    static final int MAX_SORT_FIELDS = 10;

    /**
     * Runs a permission-filtered query against the workspace store. Language defaults to {@code afts}; {@code
     * cmis} is also accepted. The query must contain 1..4096 characters and a page must contain 1..1000 entries
     * (zero selects 100).
     *
     * <p>AFTS accepts up to ten ordered property/relevance sort fields. CMIS queries must use {@code ORDER BY}
     * instead. Invalid queries or sort fields yield {@code INVALID_ARGUMENT}. Indexed nodes that no longer
     * exist are omitted without recomputing the search engine's total count.
     *
     * @param request query text, language, pagination and optional ordered AFTS sort fields
     * @param observer receives one result page with matching node snapshots, total count and continuation flag,
     *     or a gRPC error
     */
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
     * Validates and appends AFTS sort criteria in request order. Property names must resolve to dictionary
     * properties; score selects relevance. CMIS sorting belongs in the query itself.
     *
     * @param parameters search parameters to mutate
     * @param languageName remote language identifier, afts or cmis
     * @param sort ordered fields, each ascending unless explicitly descending
     * @throws IllegalArgumentException if explicit sorting is unsupported, too long or references an unknown
     *     property
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

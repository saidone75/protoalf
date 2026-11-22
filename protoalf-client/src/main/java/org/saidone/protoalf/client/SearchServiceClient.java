package org.saidone.protoalf.client;

import io.grpc.*;
import org.saidone.protoalf.api.*;
import org.saidone.protoalf.api.SearchServiceGrpc.SearchServiceBlockingStub;

import java.time.Duration;
import java.util.List;

/**
 * Client of the platform's search service, supporting AFTS, CMIS, pagination and ordered AFTS sort fields.
 *
 * <p>Each call sends the shared provider's Alfresco ticket and a per-call deadline. Unary calls retry once
 * after UNAUTHENTICATED if the provider supplies a different ticket. The caller owns the channel and should
 * share one TicketProvider across service clients.
 */
public class SearchServiceClient {

    private final SearchServiceBlockingStub stub;
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
    public SearchServiceClient(Channel channel, TicketProvider tickets, Duration deadline) {
        this(SearchServiceGrpc.newBlockingStub(channel), tickets, deadline);
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
    public SearchServiceClient(SearchServiceBlockingStub stub, TicketProvider tickets, Duration deadline) {
        this.stub = stub;
        this.support = new ClientCallSupport(tickets, deadline);
    }

    /**
     * Runs an AFTS or CMIS query with pagination and optional ordered AFTS sorting.
     *
     * @param query repository query text
     * @param language afts or cmis; an empty value selects afts
     * @param skipCount non-negative number of entries to skip
     * @param maxItems page size in 1..1000; zero selects the server default
     * @return the result page, total count and continuation flag
     */
    public SearchResponse query(String query, String language, int skipCount, int maxItems) {
        return query(query, language, skipCount, maxItems, List.of());
    }

    /**
     * Runs an AFTS or CMIS query with pagination and optional ordered AFTS sorting.
     *
     * @param query repository query text
     * @param language afts or cmis; an empty value selects afts
     * @param skipCount non-negative number of entries to skip
     * @param maxItems page size in 1..1000; zero selects the server default
     * @param sort ordered AFTS sort fields; CMIS queries use ORDER BY instead
     * @return the result page, total count and continuation flag
     */
    public SearchResponse query(String query, String language, int skipCount, int maxItems, List<SortField> sort) {
        return support.call(stub, s -> s.query(SearchRequest.newBuilder().setQuery(query).setLanguage(language).setSkipCount(skipCount).setMaxItems(maxItems).addAllSort(sort).build()));
    }
}

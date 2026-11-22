package org.saidone.protoalf.client;

import io.grpc.*;
import lombok.val;
import org.saidone.protoalf.api.*;
import org.saidone.protoalf.api.DictionaryServiceGrpc.DictionaryServiceBlockingStub;

import java.time.Duration;

/**
 * Client of the platform's dictionary service for model listings and qualified model lookups.
 *
 * <p>Each call sends the shared provider's Alfresco ticket and a per-call deadline. Unary calls retry once
 * after UNAUTHENTICATED if the provider supplies a different ticket. The caller owns the channel and should
 * share one TicketProvider across service clients.
 */
public class DictionaryServiceClient {

    private final DictionaryServiceBlockingStub stub;
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
    public DictionaryServiceClient(Channel channel, TicketProvider tickets, Duration deadline) {
        this(DictionaryServiceGrpc.newBlockingStub(channel), tickets, deadline);
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
    public DictionaryServiceClient(DictionaryServiceBlockingStub stub, TicketProvider tickets, Duration deadline) {
        this.stub = stub;
        this.support = new ClientCallSupport(tickets, deadline);
    }

    /**
     * Lists deployed models, optionally filtered by model name or namespace prefix.
     *
     * @param model model name or namespace prefix for listings; qualified model name for a single lookup
     * @param includeProperties whether to expand directly declared properties; listings require a model filter
     * @return the matching model descriptions
     */
    public ListModelsResponse getAllModels(String model, boolean includeProperties) {
        return support.call(stub, s -> s.getAllModels(ListModelsRequest.newBuilder().setModel(model).setIncludeProperties(includeProperties).build()));
    }

    /**
     * Lists all deployed models without expanding property definitions.
     *
     * @return the matching model descriptions
     */
    public ListModelsResponse getAllModels() {
        return getAllModels("", false);
    }

    /**
     * Looks up one qualified model name, optionally expanding its declared properties.
     *
     * @param model model name or namespace prefix for listings; qualified model name for a single lookup
     * @param includeProperties whether to expand directly declared properties; listings require a model filter
     * @return the requested model description
     */
    public ModelInfo getModel(String model, boolean includeProperties) {
        val request = ListModelsRequest.newBuilder().setModel(model).setIncludeProperties(includeProperties).build();
        return support.call(stub, service -> service.getModel(request));
    }
}

package org.saidone.protoalf.client;

import io.grpc.*;
import org.saidone.protoalf.api.*;
import org.saidone.protoalf.api.VersionServiceGrpc.VersionServiceBlockingStub;

import java.time.Duration;

/**
 * Client of the platform's version service. Frozen version references can be read through ContentServiceClient.
 *
 * <p>Each call sends the shared provider's Alfresco ticket and a per-call deadline. Unary calls retry once
 * after UNAUTHENTICATED if the provider supplies a different ticket. The caller owns the channel and should
 * share one TicketProvider across service clients.
 */
public class VersionServiceClient {

    private final VersionServiceBlockingStub stub;
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
    public VersionServiceClient(Channel channel, TicketProvider tickets, Duration deadline) {
        this(VersionServiceGrpc.newBlockingStub(channel), tickets, deadline);
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
    public VersionServiceClient(VersionServiceBlockingStub stub, TicketProvider tickets, Duration deadline) {
        this.stub = stub;
        this.support = new ClientCallSupport(tickets, deadline);
    }

    /**
     * Version history of a document, newest first; empty when it is not versionable. Pass the version_ref of a
     * version to {@link ContentServiceClient#getReader} or {@link ContentServiceClient#downloadContent} to read
     * that version.
     *
     * @param nodeRef node reference, bare workspace ID or supported repository alias
     * @return the version history, empty when the node has no versions
     */
    public ListVersionsResponse getVersionHistory(String nodeRef) {
        return support.call(stub, s -> s.getVersionHistory(ListVersionsRequest.newBuilder().setNodeRef(nodeRef).build()));
    }
}

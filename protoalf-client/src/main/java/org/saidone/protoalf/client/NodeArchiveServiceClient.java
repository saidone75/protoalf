package org.saidone.protoalf.client;

import io.grpc.*;
import org.saidone.protoalf.api.*;
import org.saidone.protoalf.api.NodeArchiveServiceGrpc.NodeArchiveServiceBlockingStub;

import java.time.Duration;

/**
 * Client of the platform's node archive service: lists and permanently purges the nodes in the trashcan.
 *
 * <p>Each call sends the shared provider's Alfresco ticket and a per-call deadline. Unary calls retry once
 * after UNAUTHENTICATED if the provider supplies a different ticket. The caller owns the channel and should
 * share one TicketProvider across service clients.
 */
public class NodeArchiveServiceClient {

    private final NodeArchiveServiceBlockingStub stub;
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
    public NodeArchiveServiceClient(Channel channel, TicketProvider tickets, Duration deadline) {
        this(NodeArchiveServiceGrpc.newBlockingStub(channel), tickets, deadline);
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
    public NodeArchiveServiceClient(NodeArchiveServiceBlockingStub stub, TicketProvider tickets, Duration deadline) {
        this.stub = stub;
        this.support = new ClientCallSupport(tickets, deadline);
    }

    /**
     * Lists the archived nodes visible to the caller, most recently deleted first: every node for an
     * administrator, otherwise only the nodes the caller deleted.
     *
     * @param filter optional name filter; empty lists every visible node
     * @param skipCount non-negative number of entries to skip
     * @param maxItems page size in 1..1000; zero selects the server default
     * @return the result page, total count and continuation flag
     */
    public ListArchivedNodesResponse listArchivedNodes(String filter, int skipCount, int maxItems) {
        return support.call(stub, s -> s.listArchivedNodes(ListArchivedNodesRequest.newBuilder().setFilter(filter).setSkipCount(skipCount).setMaxItems(maxItems).build()));
    }

    /**
     * Permanently deletes one archived node; it cannot be restored afterwards.
     *
     * @param nodeRef archived node reference (archive://SpacesStore/&lt;id&gt;) or bare node ID
     * @return the number of purged nodes
     */
    public long purgeArchivedNode(String nodeRef) {
        return support.call(stub, s -> s.purgeArchivedNode(PurgeArchivedNodeRequest.newBuilder().setNodeRef(nodeRef).build()).getPurged());
    }

    /**
     * Permanently deletes every archived node the caller can list: the whole trashcan for an administrator.
     *
     * @return the number of purged nodes
     */
    public long purgeAllArchivedNodes() {
        return support.call(stub, s -> s.purgeAllArchivedNodes(PurgeAllArchivedNodesRequest.getDefaultInstance()).getPurged());
    }
}

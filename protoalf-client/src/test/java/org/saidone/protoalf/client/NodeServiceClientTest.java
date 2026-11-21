package org.saidone.protoalf.client;

import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.Server;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.ServerInterceptors;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.saidone.protoalf.api.CreateNodeRequest;
import org.saidone.protoalf.api.GetNodeRequest;
import org.saidone.protoalf.api.Node;
import org.saidone.protoalf.api.NodeServiceGrpc;
import org.saidone.protoalf.api.UpdateNodeRequest;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Runs the client against an in-process server accepting only the ticket TICKET_OK. */
class NodeServiceClientTest {

    private final List<String> receivedTickets = new CopyOnWriteArrayList<>();
    private final List<CreateNodeRequest> createRequests = new CopyOnWriteArrayList<>();
    private final List<UpdateNodeRequest> updateRequests = new CopyOnWriteArrayList<>();
    private Server server;
    private ManagedChannel channel;

    @BeforeEach
    void setUp() throws Exception {
        String name = InProcessServerBuilder.generateName();
        NodeServiceGrpc.NodeServiceImplBase service = new NodeServiceGrpc.NodeServiceImplBase() {
            @Override
            public void getNode(GetNodeRequest request, StreamObserver<Node> observer) {
                observer.onNext(Node.newBuilder().setNodeRef(request.getNodeRef()).setName("Budget.xlsx").build());
                observer.onCompleted();
            }

            @Override
            public void createNode(CreateNodeRequest request, StreamObserver<Node> observer) {
                createRequests.add(request);
                observer.onNext(Node.newBuilder().setName(request.getName()).build());
                observer.onCompleted();
            }

            @Override
            public void updateNode(UpdateNodeRequest request, StreamObserver<Node> observer) {
                updateRequests.add(request);
                observer.onNext(Node.newBuilder().setNodeRef(request.getNodeRef()).setName(request.getName()).build());
                observer.onCompleted();
            }
        };
        ServerInterceptor ticketCheck = new ServerInterceptor() {
            @Override
            public <Q, R> ServerCall.Listener<Q> interceptCall(ServerCall<Q, R> call, Metadata headers,
                                                               ServerCallHandler<Q, R> next) {
                String ticket = headers.get(NodeServiceClient.TICKET_HEADER);
                receivedTickets.add(String.valueOf(ticket));
                if (!"TICKET_OK".equals(ticket)) {
                    call.close(Status.UNAUTHENTICATED, new Metadata());
                    return new ServerCall.Listener<>() { };
                }
                return next.startCall(call, headers);
            }
        };
        server = InProcessServerBuilder.forName(name).directExecutor()
                .addService(ServerInterceptors.intercept(service, ticketCheck)).build().start();
        channel = InProcessChannelBuilder.forName(name).directExecutor().build();
    }

    @AfterEach
    void tearDown() {
        channel.shutdownNow();
        server.shutdownNow();
    }

    @Test
    void sendsTheTicketInTheHeader() {
        var client = new NodeServiceClient(channel, TicketProvider.of("TICKET_OK"), Duration.ofSeconds(5));

        assertThat(client.getNode("-my-").getName()).isEqualTo("Budget.xlsx");
        assertThat(receivedTickets).containsExactly("TICKET_OK");
    }

    @Test
    void expiredTicketIsRenewedAndTheCallRetriedOnce() {
        var tickets = new TicketProvider() {
            String current = "TICKET_EXPIRED";

            @Override
            public String ticket() {
                return current;
            }

            @Override
            public void invalidate(String expired) {
                current = "TICKET_OK";
            }
        };
        var client = new NodeServiceClient(channel, tickets, Duration.ofSeconds(5));

        assertThat(client.getNode("-my-").getName()).isEqualTo("Budget.xlsx");
        assertThat(receivedTickets).containsExactly("TICKET_EXPIRED", "TICKET_OK");
    }

    @Test
    void fixedTicketIsNotRetried() {
        var client = new NodeServiceClient(channel, TicketProvider.of("TICKET_BAD"), Duration.ofSeconds(5));

        assertThatThrownBy(() -> client.getNode("-my-")).isInstanceOf(StatusRuntimeException.class)
                .hasMessageContaining("UNAUTHENTICATED");
        assertThat(receivedTickets).containsExactly("TICKET_BAD");
    }

    @Test
    void createNodeSendsPropertyValuesAndAspects() {
        var client = new NodeServiceClient(channel, TicketProvider.of("TICKET_OK"), Duration.ofSeconds(5));

        Node node = client.createNode("workspace://SpacesStore/parent", "Invoice.pdf", "cm:content",
                Map.of("cm:title", "Invoice"), Map.of("cm:taggable", List.of("finance", "2026")),
                List.of("cm:titled"), null);

        assertThat(node.getName()).isEqualTo("Invoice.pdf");
        CreateNodeRequest sent = createRequests.get(0);
        assertThat(sent.getPropertiesMap()).containsExactly(Map.entry("cm:title", "Invoice"));
        assertThat(sent.getPropertyValuesMap().get("cm:taggable").getValuesList()).containsExactly("finance", "2026");
        assertThat(sent.getAspectsList()).containsExactly("cm:titled");
    }

    @Test
    void createNodeWithoutPropertyValuesSendsNone() {
        var client = new NodeServiceClient(channel, TicketProvider.of("TICKET_OK"), Duration.ofSeconds(5));

        client.createNode("workspace://SpacesStore/parent", "Projects", "cm:folder", Map.of("cm:title", "P"), null);

        CreateNodeRequest sent = createRequests.get(0);
        assertThat(sent.getPropertyValuesMap()).isEmpty();
        assertThat(sent.getAspectsList()).isEmpty();
        assertThat(sent.hasContent()).isFalse();
    }

    @Test
    void updateNodeSendsPropertyValuesAndAspects() {
        var client = new NodeServiceClient(channel, TicketProvider.of("TICKET_OK"), Duration.ofSeconds(5));

        client.updateNode("workspace://SpacesStore/doc", "", "", Map.of(), Map.of("cm:taggable", List.of()),
                List.of("cm:taggable"), List.of("cm:titled"));

        UpdateNodeRequest sent = updateRequests.get(0);
        assertThat(sent.getPropertyValuesMap()).containsKey("cm:taggable");
        assertThat(sent.getPropertyValuesMap().get("cm:taggable").getValuesList()).isEmpty();
        assertThat(sent.getAddAspectsList()).containsExactly("cm:taggable");
        assertThat(sent.getRemoveAspectsList()).containsExactly("cm:titled");
    }

    @Test
    void updateNodeWithoutTheNewArgumentsSendsNone() {
        var client = new NodeServiceClient(channel, TicketProvider.of("TICKET_OK"), Duration.ofSeconds(5));

        client.updateNode("workspace://SpacesStore/doc", "renamed.txt", Map.of("cm:title", "T"));

        UpdateNodeRequest sent = updateRequests.get(0);
        assertThat(sent.getPropertyValuesMap()).isEmpty();
        assertThat(sent.getAddAspectsList()).isEmpty();
        assertThat(sent.getRemoveAspectsList()).isEmpty();
        assertThat(sent.getPropertiesMap()).containsExactly(Map.entry("cm:title", "T"));
    }
}

package org.saidone.protoalf.client;

import com.google.protobuf.ByteString;
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
import lombok.val;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.saidone.protoalf.api.*;
import org.saidone.protoalf.api.CreateNodeRequest;
import org.saidone.protoalf.api.FileFolderServiceGrpc;
import org.saidone.protoalf.api.GetNodeRequest;
import org.saidone.protoalf.api.Node;
import org.saidone.protoalf.api.NodeServiceGrpc;
import org.saidone.protoalf.api.UpdateNodeRequest;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Runs the client against an in-process server accepting only the ticket TICKET_OK.
 */
class NodeServiceClientTest {

    private final List<String> receivedTickets = new CopyOnWriteArrayList<>();
    private final List<CreateNodeRequest> createRequests = new CopyOnWriteArrayList<>();
    private final List<UpdateNodeRequest> updateRequests = new CopyOnWriteArrayList<>();
    private Server server;
    private ManagedChannel channel;

    @BeforeEach
    void setUp() throws Exception {
        val name = InProcessServerBuilder.generateName();
        val service = new NodeServiceGrpc.NodeServiceImplBase() {

            @Override public void getNode(GetNodeRequest request, StreamObserver<Node> observer) {
                observer.onNext(Node.newBuilder().setNodeRef(request.getNodeRef()).setName("Budget.xlsx").build());
                observer.onCompleted();
            }

            @Override public void updateNode(UpdateNodeRequest request, StreamObserver<Node> observer) {
                updateRequests.add(request);
                observer.onNext(Node.newBuilder().setNodeRef(request.getNodeRef()).setName(request.getName()).build());
                observer.onCompleted();
            }
        };
        val files = new FileFolderServiceGrpc.FileFolderServiceImplBase() {

            @Override public void create(CreateNodeRequest request, StreamObserver<Node> observer) {
                createRequests.add(request);
                observer.onNext(Node.newBuilder().setName(request.getName()).build());
                observer.onCompleted();
            }
        };
        val search = new SearchServiceGrpc.SearchServiceImplBase() {

            @Override public void query(SearchRequest request, StreamObserver<SearchResponse> observer) {
                observer.onNext(SearchResponse.newBuilder().setTotalItems(17).build());
                observer.onCompleted();
            }
        };
        val versions = new VersionServiceGrpc.VersionServiceImplBase() {

            @Override public void getVersionHistory(ListVersionsRequest request, StreamObserver<ListVersionsResponse> observer) {
                observer.onNext(ListVersionsResponse.newBuilder().addVersions(VersionInfo.newBuilder().setLabel("1.0")).build());
                observer.onCompleted();
            }
        };
        val dictionary = new DictionaryServiceGrpc.DictionaryServiceImplBase() {

            @Override public void getAllModels(ListModelsRequest request, StreamObserver<ListModelsResponse> observer) {
                observer.onNext(ListModelsResponse.newBuilder().addModels(ModelInfo.newBuilder().setName("cm:contentmodel")).build());
                observer.onCompleted();
            }
        };
        val contents = new ContentServiceGrpc.ContentServiceImplBase() {

            @Override public void getReader(GetContentRequest request, StreamObserver<GetContentResponse> observer) {
                observer.onNext(GetContentResponse.newBuilder().setContent(Content.newBuilder().setData(ByteString.copyFromUtf8("text"))).build());
                observer.onCompleted();
            }

            @Override public void getWriter(UpdateContentRequest request, StreamObserver<Node> observer) {
                observer.onNext(Node.newBuilder().setNodeRef(request.getNodeRef()).build());
                observer.onCompleted();
            }

            @Override public void downloadContent(DownloadContentRequest request, StreamObserver<DownloadContentResponse> observer) {
                observer.onNext(DownloadContentResponse.newBuilder().setInfo(ContentInfo.newBuilder().setSize(4)).build());
                observer.onNext(DownloadContentResponse.newBuilder().setData(ByteString.copyFromUtf8("text")).build());
                observer.onCompleted();
            }

            @Override public StreamObserver<UploadContentRequest> uploadContent(StreamObserver<Node> observer) {
                return new StreamObserver<>() {

                    @Override public void onNext(UploadContentRequest request) {
                    }

                    @Override public void onError(Throwable error) {
                    }

                    @Override public void onCompleted() {
                        observer.onNext(Node.newBuilder().setName("uploaded.txt").build());
                        observer.onCompleted();
                    }
                };
            }
        };
        val ticketCheck = new ServerInterceptor() {

            @Override public <Q, R> ServerCall.Listener<Q> interceptCall(ServerCall<Q, R> call, Metadata headers, ServerCallHandler<Q, R> next) {
                val ticket = headers.get(NodeServiceClient.TICKET_HEADER);
                receivedTickets.add(String.valueOf(ticket));
                if (!"TICKET_OK".equals(ticket)) {
                    call.close(Status.UNAUTHENTICATED, new Metadata());
                    return new ServerCall.Listener<>() {
                    };
                }
                return next.startCall(call, headers);
            }
        };
        server = InProcessServerBuilder.forName(name).directExecutor().addService(ServerInterceptors.intercept(service, ticketCheck)).addService(ServerInterceptors.intercept(files, ticketCheck)).addService(ServerInterceptors.intercept(search, ticketCheck)).addService(ServerInterceptors.intercept(versions, ticketCheck)).addService(ServerInterceptors.intercept(dictionary, ticketCheck)).addService(ServerInterceptors.intercept(contents, ticketCheck)).build().start();
        channel = InProcessChannelBuilder.forName(name).directExecutor().build();
    }

    @AfterEach
    void tearDown() {
        channel.shutdownNow();
        server.shutdownNow();
    }

    @Test
    void sendsTheTicketInTheHeader() {
        val client = new NodeServiceClient(channel, TicketProvider.of("TICKET_OK"), Duration.ofSeconds(5));
        assertThat(client.getNode("-my-").getName()).isEqualTo("Budget.xlsx");
        assertThat(receivedTickets).containsExactly("TICKET_OK");
    }

    @Test
    void expiredTicketIsRenewedAndTheCallRetriedOnce() {
        val tickets = new TicketProvider() {

            String current = "TICKET_EXPIRED";

            @Override public String ticket() {
                return current;
            }

            @Override public void invalidate(String expired) {
                current = "TICKET_OK";
            }
        };
        val client = new NodeServiceClient(channel, tickets, Duration.ofSeconds(5));
        assertThat(client.getNode("-my-").getName()).isEqualTo("Budget.xlsx");
        assertThat(receivedTickets).containsExactly("TICKET_EXPIRED", "TICKET_OK");
    }

    @Test
    void fixedTicketIsNotRetried() {
        val client = new NodeServiceClient(channel, TicketProvider.of("TICKET_BAD"), Duration.ofSeconds(5));
        assertThatThrownBy(() -> client.getNode("-my-")).isInstanceOf(StatusRuntimeException.class).hasMessageContaining("UNAUTHENTICATED");
        assertThat(receivedTickets).containsExactly("TICKET_BAD");
    }

    @Test
    void createNodeSendsPropertyValuesAndAspects() {
        val client = new NodeServiceClient(channel, TicketProvider.of("TICKET_OK"), Duration.ofSeconds(5));
        val node = client.createNode("workspace://SpacesStore/parent", "Invoice.pdf", "cm:content", Map.of("cm:title", "Invoice"), Map.of("cm:taggable", List.of("finance", "2026")), List.of("cm:titled"), null);
        assertThat(node.getName()).isEqualTo("Invoice.pdf");
        val sent = createRequests.get(0);
        assertThat(sent.getPropertiesMap()).containsExactly(Map.entry("cm:title", "Invoice"));
        assertThat(sent.getPropertyValuesMap().get("cm:taggable").getValuesList()).containsExactly("finance", "2026");
        assertThat(sent.getAspectsList()).containsExactly("cm:titled");
    }

    @Test
    void createNodeWithoutPropertyValuesSendsNone() {
        val client = new NodeServiceClient(channel, TicketProvider.of("TICKET_OK"), Duration.ofSeconds(5));
        client.createNode("workspace://SpacesStore/parent", "Projects", "cm:folder", Map.of("cm:title", "P"), null);
        val sent = createRequests.get(0);
        assertThat(sent.getPropertyValuesMap()).isEmpty();
        assertThat(sent.getAspectsList()).isEmpty();
        assertThat(sent.hasContent()).isFalse();
    }

    @Test
    void updateNodeSendsPropertyValuesAndAspects() {
        val client = new NodeServiceClient(channel, TicketProvider.of("TICKET_OK"), Duration.ofSeconds(5));
        client.updateNode("workspace://SpacesStore/doc", "", "", Map.of(), Map.of("cm:taggable", List.of()), List.of("cm:taggable"), List.of("cm:titled"));
        val sent = updateRequests.get(0);
        assertThat(sent.getPropertyValuesMap()).containsKey("cm:taggable");
        assertThat(sent.getPropertyValuesMap().get("cm:taggable").getValuesList()).isEmpty();
        assertThat(sent.getAddAspectsList()).containsExactly("cm:taggable");
        assertThat(sent.getRemoveAspectsList()).containsExactly("cm:titled");
    }

    @Test
    void updateNodeWithoutTheNewArgumentsSendsNone() {
        val client = new NodeServiceClient(channel, TicketProvider.of("TICKET_OK"), Duration.ofSeconds(5));
        client.updateNode("workspace://SpacesStore/doc", "renamed.txt", Map.of("cm:title", "T"));
        val sent = updateRequests.get(0);
        assertThat(sent.getPropertyValuesMap()).isEmpty();
        assertThat(sent.getAddAspectsList()).isEmpty();
        assertThat(sent.getRemoveAspectsList()).isEmpty();
        assertThat(sent.getPropertiesMap()).containsExactly(Map.entry("cm:title", "T"));
    }

    @Test
    void facadeDispatchesToTheMatchingAlfrescoServices() {
        val client = new NodeServiceClient(channel, TicketProvider.of("TICKET_OK"), Duration.ofSeconds(5));
        assertThat(client.search("TYPE:cm:content", "afts", 0, 10).getTotalItems()).isEqualTo(17);
        assertThat(client.listVersions("doc").getVersions(0).getLabel()).isEqualTo("1.0");
        assertThat(client.listModels("", false).getModels(0).getName()).isEqualTo("cm:contentmodel");
        assertThat(client.getContent("doc", true, 0, 100).getContent().getData().toStringUtf8()).isEqualTo("text");
        assertThat(client.updateContent("doc", Content.getDefaultInstance()).getNodeRef()).isEqualTo("doc");
        val out = new ByteArrayOutputStream();
        assertThat(client.downloadContent("doc", out).getSize()).isEqualTo(4);
        assertThat(out.toString()).isEqualTo("text");
        assertThat(receivedTickets).hasSize(6).containsOnly("TICKET_OK");
    }

    @Test
    void nonNodeServiceCallsAlsoRenewExpiredTickets() {
        val tickets = new TicketProvider() {

            String current = "TICKET_EXPIRED";

            @Override public String ticket() {
                return current;
            }

            @Override public void invalidate(String expired) {
                current = "TICKET_OK";
            }
        };
        val client = new NodeServiceClient(channel, tickets, Duration.ofSeconds(5));
        assertThat(client.search("x", "afts", 0, 10).getTotalItems()).isEqualTo(17);
        assertThat(receivedTickets).containsExactly("TICKET_EXPIRED", "TICKET_OK");
    }

    @Test
    void uploadsUseTheContentService() {
        val client = new NodeServiceClient(channel, TicketProvider.of("TICKET_OK"), Duration.ofSeconds(5));
        val content = new ByteArrayInputStream(new byte[] { 1, 2, 3 });
        assertThat(client.uploadContent(CreateDocumentHeader.newBuilder().setParentRef("folder").setName("uploaded.txt").build(), content).getName()).isEqualTo("uploaded.txt");
        assertThat(receivedTickets).containsExactly("TICKET_OK");
    }
}

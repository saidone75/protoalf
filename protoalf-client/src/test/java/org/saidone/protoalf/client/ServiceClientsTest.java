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
import io.grpc.stub.MetadataUtils;
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
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Runs the client against an in-process server accepting only the ticket TICKET_OK.
 */
class ServiceClientsTest {

    private final List<String> receivedTickets = new CopyOnWriteArrayList<>();
    private final List<CreateNodeRequest> createRequests = new CopyOnWriteArrayList<>();
    private final List<UpdateNodeRequest> updateRequests = new CopyOnWriteArrayList<>();
    private final List<Object> nodeRequests = new CopyOnWriteArrayList<>();
    private final List<String> correlations = new CopyOnWriteArrayList<>();
    private final AtomicInteger authenticationCalls = new AtomicInteger();
    private static final Metadata.Key<String> CORRELATION = Metadata.Key.of("x-correlation", Metadata.ASCII_STRING_MARSHALLER);
    private Server server;
    private ManagedChannel channel;

    @BeforeEach
    void setUp() throws Exception {
        val name = InProcessServerBuilder.generateName();
        val service = new NodeServiceGrpc.NodeServiceImplBase() {

            @Override public void getProperties(NodeRefRequest request, StreamObserver<NodeProperties> observer) {
                observer.onNext(NodeProperties.newBuilder().putProperties("cm:title", Property.newBuilder()
                        .addValues(PropertyValue.newBuilder().setStringValue("Budget")).build()).build());
                observer.onCompleted();
            }

            @Override public void getType(NodeRefRequest request, StreamObserver<NodeType> observer) {
                observer.onNext(NodeType.newBuilder().setType("cm:content").build());
                observer.onCompleted();
            }

            @Override public void getAspects(NodeRefRequest request, StreamObserver<NodeAspects> observer) {
                observer.onNext(NodeAspects.newBuilder().addAspects("cm:titled").build());
                observer.onCompleted();
            }

            @Override public void addProperties(AddPropertiesRequest request, StreamObserver<Node> observer) {
                nodeRequests.add(request);
                observer.onNext(Node.newBuilder().setNodeRef(request.getNodeRef()).build());
                observer.onCompleted();
            }

            @Override public void setType(SetTypeRequest request, StreamObserver<Node> observer) {
                nodeRequests.add(request);
                observer.onNext(Node.newBuilder().setNodeRef(request.getNodeRef()).build());
                observer.onCompleted();
            }

            @Override public void addAspect(AddAspectRequest request, StreamObserver<Node> observer) {
                nodeRequests.add(request);
                observer.onNext(Node.newBuilder().setNodeRef(request.getNodeRef()).build());
                observer.onCompleted();
            }

            @Override public void removeAspect(RemoveAspectRequest request, StreamObserver<Node> observer) {
                nodeRequests.add(request);
                observer.onNext(Node.newBuilder().setNodeRef(request.getNodeRef()).build());
                observer.onCompleted();
            }

            @Override public void deleteNode(DeleteNodeRequest request, StreamObserver<DeleteNodeResponse> observer) {
                nodeRequests.add(request);
                observer.onNext(DeleteNodeResponse.getDefaultInstance());
                observer.onCompleted();
            }

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

            @Override public void list(ListChildrenRequest request, StreamObserver<ListChildrenResponse> observer) {
                nodeRequests.add(request);
                observer.onNext(ListChildrenResponse.newBuilder().setTotalItems(request.hasCountTotal() && !request.getCountTotal() ? -1 : 3).build());
                observer.onCompleted();
            }

            @Override public void move(MoveNodeRequest request, StreamObserver<Node> observer) {
                observer.onNext(Node.newBuilder().setName(request.getName()).setParentRef(request.getTargetParentRef()).build());
                observer.onCompleted();
            }

            @Override public void copy(CopyNodeRequest request, StreamObserver<Node> observer) {
                observer.onNext(Node.newBuilder().setName(request.getName()).setParentRef(request.getTargetParentRef()).build());
                observer.onCompleted();
            }

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

            @Override public void getModel(ListModelsRequest request, StreamObserver<ModelInfo> observer) {
                observer.onNext(ModelInfo.newBuilder().setName(request.getModel()).build());
                observer.onCompleted();
            }

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
        val authentication = new AuthenticationServiceGrpc.AuthenticationServiceImplBase() {
            @Override public void authenticate(LoginRequest request, StreamObserver<LoginResponse> observer) {
                authenticationCalls.incrementAndGet();
                observer.onNext(LoginResponse.newBuilder().setTicket("TICKET_OK").build());
                observer.onCompleted();
            }
        };
        val ticketCheck = new ServerInterceptor() {

            @Override public <Q, R> ServerCall.Listener<Q> interceptCall(ServerCall<Q, R> call, Metadata headers, ServerCallHandler<Q, R> next) {
                val ticket = headers.get(ClientCallSupport.TICKET_HEADER);
                receivedTickets.add(String.valueOf(ticket));
                correlations.add(String.valueOf(headers.get(CORRELATION)));
                if (!"TICKET_OK".equals(ticket)) {
                    call.close(Status.UNAUTHENTICATED, new Metadata());
                    return new ServerCall.Listener<>() {
                    };
                }
                return next.startCall(call, headers);
            }
        };
        server = InProcessServerBuilder.forName(name).directExecutor().addService(authentication).addService(ServerInterceptors.intercept(service, ticketCheck)).addService(ServerInterceptors.intercept(files, ticketCheck)).addService(ServerInterceptors.intercept(search, ticketCheck)).addService(ServerInterceptors.intercept(versions, ticketCheck)).addService(ServerInterceptors.intercept(dictionary, ticketCheck)).addService(ServerInterceptors.intercept(contents, ticketCheck)).build().start();
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
    void createNodeSendsTypedPropertiesAndAspects() {
        val client = new FileFolderServiceClient(channel, TicketProvider.of("TICKET_OK"), Duration.ofSeconds(5));
        val node = client.create("workspace://SpacesStore/parent", "Invoice.pdf", "cm:content", Map.of("cm:title", Properties.text("Invoice"), "cm:taggable", Properties.texts("finance", "2026")), List.of("cm:titled"), null);
        assertThat(node.getName()).isEqualTo("Invoice.pdf");
        val sent = createRequests.get(0);
        assertThat(sent.getPropertiesMap()).containsEntry("cm:title", Properties.text("Invoice"));
        assertThat(sent.getPropertiesMap().get("cm:taggable")).isEqualTo(Properties.texts("finance", "2026"));
        assertThat(sent.getAspectsList()).containsExactly("cm:titled");
    }

    @Test
    void createNodePreservesScalarCardinality() {
        val client = new FileFolderServiceClient(channel, TicketProvider.of("TICKET_OK"), Duration.ofSeconds(5));
        client.create("workspace://SpacesStore/parent", "Projects", "cm:folder", Map.of("cm:title", Properties.text("P")), null);
        val sent = createRequests.get(0);
        assertThat(sent.getPropertiesMap()).containsOnlyKeys("cm:title");
        assertThat(sent.getAspectsList()).isEmpty();
        assertThat(sent.hasContent()).isFalse();
    }

    @Test
    void updateNodeSendsExplicitEmptyListAndAspects() {
        val client = new NodeServiceClient(channel, TicketProvider.of("TICKET_OK"), Duration.ofSeconds(5));
        client.updateNode("workspace://SpacesStore/doc", "", "", Map.of("cm:taggable", Properties.texts()), List.of("cm:taggable"), List.of("cm:titled"));
        val sent = updateRequests.get(0);
        assertThat(sent.getPropertiesMap()).containsKey("cm:taggable");
        assertThat(sent.getPropertiesMap().get("cm:taggable").getValuesList()).isEmpty();
        assertThat(sent.getAddAspectsList()).containsExactly("cm:taggable");
        assertThat(sent.getRemoveAspectsList()).containsExactly("cm:titled");
    }

    @Test
    void updateNodeWithoutAspectChangesSendsNone() {
        val client = new NodeServiceClient(channel, TicketProvider.of("TICKET_OK"), Duration.ofSeconds(5));
        client.updateNode("workspace://SpacesStore/doc", "renamed.txt", Map.of("cm:title", Properties.text("T")));
        val sent = updateRequests.get(0);
        assertThat(sent.getPropertiesMap()).containsOnlyKeys("cm:title");
        assertThat(sent.getAddAspectsList()).isEmpty();
        assertThat(sent.getRemoveAspectsList()).isEmpty();
        assertThat(sent.getPropertiesMap()).containsExactly(Map.entry("cm:title", Properties.text("T")));
    }

    @Test
    void clientsDispatchToTheirMatchingAlfrescoServices() {
        val tickets = TicketProvider.of("TICKET_OK");
        val deadline = Duration.ofSeconds(5);
        val search = new SearchServiceClient(channel, tickets, deadline);
        val versions = new VersionServiceClient(channel, tickets, deadline);
        val dictionary = new DictionaryServiceClient(channel, tickets, deadline);
        val contents = new ContentServiceClient(channel, tickets, deadline);
        assertThat(search.query("TYPE:cm:content", "afts", 0, 10).getTotalItems()).isEqualTo(17);
        assertThat(versions.getVersionHistory("doc").getVersions(0).getLabel()).isEqualTo("1.0");
        assertThat(dictionary.getAllModels("", false).getModels(0).getName()).isEqualTo("cm:contentmodel");
        assertThat(contents.getReader("doc", true, 0, 100).getContent().getData().toStringUtf8()).isEqualTo("text");
        assertThat(contents.getWriter("doc", Content.getDefaultInstance()).getNodeRef()).isEqualTo("doc");
        val out = new ByteArrayOutputStream();
        assertThat(contents.downloadContent("doc", out).getSize()).isEqualTo(4);
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
        val client = new SearchServiceClient(channel, tickets, Duration.ofSeconds(5));
        assertThat(client.query("x", "afts", 0, 10).getTotalItems()).isEqualTo(17);
        assertThat(receivedTickets).containsExactly("TICKET_EXPIRED", "TICKET_OK");
    }

    @Test
    void uploadsUseTheContentService() {
        val client = new ContentServiceClient(channel, TicketProvider.of("TICKET_OK"), Duration.ofSeconds(5));
        val content = new ByteArrayInputStream(new byte[] { 1, 2, 3 });
        assertThat(client.uploadContent(CreateDocumentHeader.newBuilder().setParentRef("folder").setName("uploaded.txt").build(), content).getName()).isEqualTo("uploaded.txt");
        assertThat(receivedTickets).containsExactly("TICKET_OK");
    }

    @Test
    void nativeNodeOperationsUseTheirOwnRpcAndPreservePropertyLists() {
        val client = new NodeServiceClient(channel, TicketProvider.of("TICKET_OK"), Duration.ofSeconds(5));
        assertThat(client.getProperties("doc").get("cm:title").getValues(0).getStringValue()).isEqualTo("Budget");
        assertThat(client.getType("doc")).isEqualTo("cm:content");
        assertThat(client.getAspects("doc")).containsExactly("cm:titled");
        client.addProperties("doc", Map.of("cm:title", Properties.text("New title"), "cm:taggable", Properties.texts()));
        client.setType("doc", "custom:document");
        client.addAspect("doc", "cm:titled", Map.of("cm:taggable", Properties.texts("finance")));
        client.removeAspect("doc", "cm:titled");
        client.deleteNode("doc");
        assertThat(nodeRequests).hasSize(5);
        assertThat(((AddPropertiesRequest) nodeRequests.get(0)).getPropertiesOrThrow("cm:taggable").getValuesList()).isEmpty();
        assertThat(((SetTypeRequest) nodeRequests.get(1)).getType()).isEqualTo("custom:document");
        assertThat(((AddAspectRequest) nodeRequests.get(2)).getPropertiesOrThrow("cm:taggable")).isEqualTo(Properties.texts("finance"));
        assertThat(((RemoveAspectRequest) nodeRequests.get(3)).getAspect()).isEqualTo("cm:titled");
        assertThat(((DeleteNodeRequest) nodeRequests.get(4)).getNodeRef()).isEqualTo("doc");
    }

    @Test
    void fileFolderAndDictionaryClientsExposeThePlatformOperationNames() {
        val files = new FileFolderServiceClient(channel, TicketProvider.of("TICKET_OK"), Duration.ofSeconds(5));
        assertThat(files.list("parent", 0, 100).getTotalItems()).isEqualTo(3);
        assertThat(files.move("doc", "folder", "moved.txt").getName()).isEqualTo("moved.txt");
        assertThat(files.copy("doc", "folder", "copy.txt").getParentRef()).isEqualTo("folder");
        val dictionary = new DictionaryServiceClient(channel, TicketProvider.of("TICKET_OK"), Duration.ofSeconds(5));
        assertThat(dictionary.getModel("cm:contentmodel", true).getName()).isEqualTo("cm:contentmodel");
    }

    @Test
    void listingCountSelectionPreservesLegacyAbsenceAndExplicitFlags() {
        val client = new FileFolderServiceClient(channel, TicketProvider.of("TICKET_OK"), Duration.ofSeconds(5));
        assertThat(client.list("parent", 0, 100).getTotalItems()).isEqualTo(3);
        assertThat(client.list("parent", 10, 50, false).getTotalItems()).isEqualTo(-1);
        assertThat(client.list("parent", 10, 50, true).getTotalItems()).isEqualTo(3);
        val legacy = (ListChildrenRequest) nodeRequests.get(0);
        val disabled = (ListChildrenRequest) nodeRequests.get(1);
        val exact = (ListChildrenRequest) nodeRequests.get(2);
        assertThat(legacy.hasCountTotal()).isFalse();
        assertThat(disabled.hasCountTotal()).isTrue();
        assertThat(disabled.getCountTotal()).isFalse();
        assertThat(disabled.getSkipCount()).isEqualTo(10);
        assertThat(disabled.getMaxItems()).isEqualTo(50);
        assertThat(exact.getCountTotal()).isTrue();
    }

    @Test
    void clientsShareOneLazyAuthenticationProvider() {
        val deadline = Duration.ofSeconds(5);
        val tickets = new AuthenticationServiceClient(channel, "mcp", "secret", deadline);
        assertThat(authenticationCalls).hasValue(0);
        new NodeServiceClient(channel, tickets, deadline).getNode("doc");
        new FileFolderServiceClient(channel, tickets, deadline).list("parent", 0, 100);
        new ContentServiceClient(channel, tickets, deadline).getReader("doc", false, 0, 100);
        new VersionServiceClient(channel, tickets, deadline).getVersionHistory("doc");
        new SearchServiceClient(channel, tickets, deadline).query("x", "afts", 0, 100);
        new DictionaryServiceClient(channel, tickets, deadline).getAllModels();
        assertThat(authenticationCalls).hasValue(1);
        assertThat(receivedTickets).hasSize(6).containsOnly("TICKET_OK");
    }

    @Test
    void preconfiguredStubInterceptorsSurviveTicketAttachment() {
        val headers = new Metadata();
        headers.put(CORRELATION, "request-123");
        val stub = NodeServiceGrpc.newBlockingStub(channel).withInterceptors(MetadataUtils.newAttachHeadersInterceptor(headers));
        new NodeServiceClient(stub, TicketProvider.of("TICKET_OK"), Duration.ofSeconds(5)).getNode("doc");
        assertThat(correlations).containsExactly("request-123");
        assertThat(receivedTickets).containsExactly("TICKET_OK");
    }

}

package org.saidone.protoalf.mcp;

import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.saidone.protoalf.api.Node;
import org.saidone.protoalf.api.NodeServiceGrpc;
import org.saidone.protoalf.api.UpdateNodeRequest;
import org.saidone.protoalf.client.NodeServiceClient;
import org.saidone.protoalf.client.Properties;
import org.saidone.protoalf.client.TicketProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

class TypedPropertiesToolsTest {

    private final List<UpdateNodeRequest> requests = new ArrayList<>();
    private ManagedChannel channel;
    private Server server;
    private NodeServiceClient nodes;

    @BeforeEach
    void setUp() throws Exception {
        var name = InProcessServerBuilder.generateName();
        server = InProcessServerBuilder.forName(name).directExecutor().addService(new NodeServiceGrpc.NodeServiceImplBase() {
            @Override public void updateNode(UpdateNodeRequest request, StreamObserver<Node> observer) {
                requests.add(request);
                observer.onNext(Node.getDefaultInstance());
                observer.onCompleted();
            }
        }).build().start();
        channel = InProcessChannelBuilder.forName(name).directExecutor().build();
        nodes = new NodeServiceClient(channel, TicketProvider.of("ticket"), Duration.ofSeconds(5));
    }

    @AfterEach
    void tearDown() {
        channel.shutdownNow();
        server.shutdownNow();
    }

    @Test
    void mcpCallDeserializesTypedScalarsListsAndNulls() {
        var callback = Arrays.stream(MethodToolCallbackProvider.builder().toolObjects(new NodeServiceTools(nodes))
                .build().getToolCallbacks()).filter(tool -> tool.getToolDefinition().name().equals("alfresco_update_node"))
                .findFirst().orElseThrow();
        assertThat(callback.getToolDefinition().inputSchema()).contains("multiValued", "values", "integerValue", "nullValue");
        callback.call("""
                {"nodeRef":"doc","properties":{
                  "cm:title":{"multiValued":false,"values":[{"stringValue":"Budget"}]},
                  "my:counts":{"multiValued":true,"values":[{"integerValue":0}]},
                  "my:tags":{"multiValued":true,"values":[]},
                  "my:enabled":{"multiValued":false,"values":[{"booleanValue":false}]},
                  "my:optional":{"multiValued":false,"values":[{"nullValue":true}]}
                }}
                """);
        assertThat(requests).hasSize(1);
        assertThat(requests.get(0).getPropertiesMap()).isEqualTo(Map.of(
                "cm:title", Properties.text("Budget"), "my:counts", Properties.integers(0),
                "my:tags", Properties.texts(), "my:enabled", Properties.bool(false), "my:optional", Properties.nullValue()));
    }

    @Test
    void ambiguousOrMalformedValuesAreRejectedBeforeTheRpc() {
        var callback = Arrays.stream(MethodToolCallbackProvider.builder().toolObjects(new NodeServiceTools(nodes))
                .build().getToolCallbacks()).filter(tool -> tool.getToolDefinition().name().equals("alfresco_update_node"))
                .findFirst().orElseThrow();
        for (var property : new String[] {
                "null", "{\"multiValued\":false,\"values\":[]}",
                "{\"multiValued\":false,\"values\":[{}]}",
                "{\"multiValued\":false,\"values\":[{\"stringValue\":\"a\",\"integerValue\":1}]}",
                "{\"multiValued\":true,\"values\":[{\"nullValue\":true}]}",
                "{\"multiValued\":false,\"values\":[{\"nullValue\":false}]}"
        }) {
            assertThatThrownBy(() -> callback.call("{\"nodeRef\":\"doc\",\"properties\":{\"my:value\":" + property + "}}"))
                    .isInstanceOf(RuntimeException.class);
        }
        assertThat(requests).isEmpty();
    }
}

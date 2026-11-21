package org.saidone.protoalf.mcp;

import io.grpc.ManagedChannel;
import lombok.val;
import org.saidone.protoalf.client.AlfrescoGrpcChannels;
import org.saidone.protoalf.client.AuthenticationServiceClient;
import org.saidone.protoalf.client.BulkExporter;
import org.saidone.protoalf.client.NodeServiceClient;
import org.saidone.protoalf.client.TicketProvider;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;

@Configuration(proxyBeanMethods = false)
public class AlfrescoConfiguration {

    /** gRPC channel to the protoalf-platform module. Connects lazily, on the first call. */
    @Bean(destroyMethod = "shutdownNow")
    ManagedChannel alfrescoGrpcChannel(AlfrescoProperties properties) throws IOException {
        val grpc = properties.grpc();
        return AlfrescoGrpcChannels.create(grpc.host(), grpc.port(), grpc.trustCertificate(), grpc.authorityOverride());
    }

    /** Logs in through the Login RPC lazily, on the first tool call: every call runs as the configured user. */
    @Bean
    TicketProvider alfrescoTicketProvider(ManagedChannel alfrescoGrpcChannel, AlfrescoProperties properties) {
        return new AuthenticationServiceClient(alfrescoGrpcChannel, properties.username(), properties.password(),
                properties.grpc().deadline());
    }

    @Bean
    NodeServiceClient nodeServiceClient(ManagedChannel alfrescoGrpcChannel, TicketProvider tickets,
                                          AlfrescoProperties properties) {
        return new NodeServiceClient(alfrescoGrpcChannel, tickets, properties.grpc().deadline());
    }

    @Bean
    AlfrescoTools alfrescoTools(NodeServiceClient client) {
        return new AlfrescoTools(client);
    }

    /** Its own client: downloads of an export can take longer than the deadline of the tool calls. */
    @Bean
    ExportTools exportTools(ManagedChannel alfrescoGrpcChannel, TicketProvider tickets, AlfrescoProperties properties) {
        val client = new NodeServiceClient(alfrescoGrpcChannel, tickets, properties.export().deadline());
        return new ExportTools(new BulkExporter(client), properties.export().directory());
    }

    /** Picked up by the Spring AI MCP server auto-configuration and published as MCP tools. */
    @Bean
    ToolCallbackProvider alfrescoToolCallbacks(AlfrescoTools tools, ExportTools exportTools) {
        return MethodToolCallbackProvider.builder().toolObjects(tools, exportTools).build();
    }
}

package org.saidone.protoalf.mcp;

import io.grpc.ManagedChannel;
import lombok.val;
import org.saidone.protoalf.client.AlfrescoGrpcChannels;
import org.saidone.protoalf.client.AuthenticationServiceClient;
import org.saidone.protoalf.client.ContentServiceClient;
import org.saidone.protoalf.client.DictionaryServiceClient;
import org.saidone.protoalf.client.FileFolderServiceClient;
import org.saidone.protoalf.client.NodeArchiveServiceClient;
import org.saidone.protoalf.client.NodeServiceClient;
import org.saidone.protoalf.client.SearchServiceClient;
import org.saidone.protoalf.client.TicketProvider;
import org.saidone.protoalf.client.VersionServiceClient;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;

@Configuration(proxyBeanMethods = false)
public class AlfrescoConfiguration {

    /**
     * gRPC channel to the protoalf-platform module. Connects lazily, on the first call.
     */
    @Bean(destroyMethod = "shutdownNow")
    ManagedChannel alfrescoGrpcChannel(AlfrescoProperties properties) throws IOException {
        val grpc = properties.grpc();
        return AlfrescoGrpcChannels.create(grpc.host(), grpc.port(), grpc.trustCertificate(), grpc.authorityOverride());
    }

    /**
     * Logs in through the Authenticate RPC lazily, on the first tool call: every call runs as the configured
     * user.
     */
    @Bean
    AuthenticationServiceClient alfrescoTicketProvider(ManagedChannel alfrescoGrpcChannel, AlfrescoProperties properties) {
        return new AuthenticationServiceClient(alfrescoGrpcChannel, properties.username(), properties.password(), properties.grpc().deadline());
    }

    @Bean
    NodeServiceClient nodeServiceClient(ManagedChannel alfrescoGrpcChannel, TicketProvider tickets, AlfrescoProperties properties) {
        return new NodeServiceClient(alfrescoGrpcChannel, tickets, properties.grpc().deadline());
    }

    @Bean
    FileFolderServiceClient fileFolderServiceClient(ManagedChannel channel, TicketProvider tickets, AlfrescoProperties properties) {
        return new FileFolderServiceClient(channel, tickets, properties.grpc().deadline());
    }

    @Bean
    ContentServiceClient contentServiceClient(ManagedChannel channel, TicketProvider tickets, AlfrescoProperties properties) {
        return new ContentServiceClient(channel, tickets, properties.grpc().deadline());
    }

    @Bean
    VersionServiceClient versionServiceClient(ManagedChannel channel, TicketProvider tickets, AlfrescoProperties properties) {
        return new VersionServiceClient(channel, tickets, properties.grpc().deadline());
    }

    @Bean
    SearchServiceClient searchServiceClient(ManagedChannel channel, TicketProvider tickets, AlfrescoProperties properties) {
        return new SearchServiceClient(channel, tickets, properties.grpc().deadline());
    }

    @Bean
    DictionaryServiceClient dictionaryServiceClient(ManagedChannel channel, TicketProvider tickets, AlfrescoProperties properties) {
        return new DictionaryServiceClient(channel, tickets, properties.grpc().deadline());
    }

    @Bean
    NodeArchiveServiceClient nodeArchiveServiceClient(ManagedChannel channel, TicketProvider tickets, AlfrescoProperties properties) {
        return new NodeArchiveServiceClient(channel, tickets, properties.grpc().deadline());
    }

    @Bean
    NodeServiceTools nodeServiceTools(NodeServiceClient nodes) {
        return new NodeServiceTools(nodes);
    }

    @Bean
    NodeArchiveServiceTools nodeArchiveServiceTools(NodeArchiveServiceClient archive) {
        return new NodeArchiveServiceTools(archive);
    }

    @Bean
    FileFolderServiceTools fileFolderServiceTools(FileFolderServiceClient files) {
        return new FileFolderServiceTools(files);
    }

    @Bean
    ContentServiceTools contentServiceTools(ContentServiceClient contents) {
        return new ContentServiceTools(contents);
    }

    @Bean
    SearchServiceTools searchServiceTools(SearchServiceClient search) {
        return new SearchServiceTools(search);
    }

    @Bean
    DictionaryServiceTools dictionaryServiceTools(DictionaryServiceClient dictionary) {
        return new DictionaryServiceTools(dictionary);
    }

    /**
     * Picked up by the Spring AI MCP server auto-configuration and published as MCP tools.
     */
    @Bean
    ToolCallbackProvider alfrescoToolCallbacks(NodeServiceTools nodes, NodeArchiveServiceTools archive,
                                               FileFolderServiceTools files, ContentServiceTools contents,
                                               SearchServiceTools search, DictionaryServiceTools dictionary) {
        return MethodToolCallbackProvider.builder().toolObjects(nodes, archive, files, contents, search, dictionary).build();
    }
}

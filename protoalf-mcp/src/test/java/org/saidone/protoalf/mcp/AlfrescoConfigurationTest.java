package org.saidone.protoalf.mcp;

import org.junit.jupiter.api.Test;
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
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;

class AlfrescoConfigurationTest {

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(AlfrescoProperties.class)
    @Import(AlfrescoConfiguration.class)
    static class TestConfiguration {
    }

    @Test
    void registersAllServiceClientsAndPreservesTheMcpToolContract() {
        new ApplicationContextRunner().withUserConfiguration(TestConfiguration.class).run(context -> {
            assertThat(context).hasNotFailed()
                    .hasSingleBean(AuthenticationServiceClient.class)
                    .hasSingleBean(TicketProvider.class)
                    .hasSingleBean(NodeServiceClient.class)
                    .hasSingleBean(NodeArchiveServiceClient.class)
                    .hasSingleBean(FileFolderServiceClient.class)
                    .hasSingleBean(ContentServiceClient.class)
                    .hasSingleBean(VersionServiceClient.class)
                    .hasSingleBean(SearchServiceClient.class)
                    .hasSingleBean(DictionaryServiceClient.class)
                    .hasSingleBean(NodeServiceTools.class)
                    .hasSingleBean(NodeArchiveServiceTools.class)
                    .hasSingleBean(FileFolderServiceTools.class)
                    .hasSingleBean(ContentServiceTools.class)
                    .hasSingleBean(SearchServiceTools.class)
                    .hasSingleBean(DictionaryServiceTools.class)
                    .hasSingleBean(ExportTools.class);
            assertThat(context.getBean(ToolCallbackProvider.class).getToolCallbacks())
                    .hasSize(17)
                    .extracting(callback -> callback.getToolDefinition().name())
                    .containsExactlyInAnyOrder(
                            "alfresco_get_node", "alfresco_update_node", "alfresco_delete_node",
                            "alfresco_list_trashcan", "alfresco_purge_trashcan_node", "alfresco_empty_trashcan",
                            "alfresco_list_children", "alfresco_create_folder", "alfresco_create_document",
                            "alfresco_move_node", "alfresco_copy_node",
                            "alfresco_read_content", "alfresco_update_content",
                            "alfresco_search", "alfresco_list_models",
                            "alfresco_export_bulk", "alfresco_export_status");
        });
    }
}

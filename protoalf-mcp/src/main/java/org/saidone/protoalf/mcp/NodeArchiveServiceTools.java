package org.saidone.protoalf.mcp;

import io.grpc.StatusRuntimeException;
import lombok.RequiredArgsConstructor;
import org.saidone.protoalf.client.NodeArchiveServiceClient;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import static org.saidone.protoalf.mcp.ToolSupport.*;

/** MCP tools backed by NodeArchiveServiceClient. */
@RequiredArgsConstructor
public class NodeArchiveServiceTools {

    private final NodeArchiveServiceClient archive;

    @Tool(name = "alfresco_list_trashcan", description = """
        Lists the deleted documents and folders in the Alfresco trashcan, most recently deleted first, \
        with paging. An administrator sees every deleted node, other users only the nodes they deleted. \
        Entries are archive://SpacesStore nodeRefs; sys:archivedDate, sys:archivedBy and \
        sys:archivedOriginalParentAssoc tell when, by whom and from where each node was deleted. \
        While 'hasMoreItems' is true, increase skipCount by the page size to read the next page.""")
    public String listTrashcan(@ToolParam(description = "Optional name filter", required = false) String filter, @ToolParam(description = "Number of items to skip (paging offset), 0 for the first page", required = false) Integer skipCount, @ToolParam(description = "Page size, 1..1000 (default 100)", required = false) Integer maxItems) {
        try {
            return json(archive.listArchivedNodes(text(filter), skipCount == null ? 0 : skipCount, maxItems == null ? 100 : maxItems));
        } catch (StatusRuntimeException e) {
            throw error(e);
        }
    }

    @Tool(name = "alfresco_purge_trashcan_node", description = """
        Permanently deletes one document or folder from the Alfresco trashcan: it cannot be restored \
        afterwards. Ask the user for confirmation before purging.""")
    public String purgeTrashcanNode(@ToolParam(description = ARCHIVED_NODE_PARAM) String nodeRef) {
        try {
            archive.purgeArchivedNode(nodeRef);
            return "Purged %s (permanently deleted)".formatted(nodeRef);
        } catch (StatusRuntimeException e) {
            throw error(e);
        }
    }

    @Tool(name = "alfresco_empty_trashcan", description = """
        Permanently deletes every node in the Alfresco trashcan that the configured user can see (all of \
        them for an administrator): they cannot be restored afterwards. Ask the user for confirmation first.""")
    public String emptyTrashcan() {
        try {
            return "Purged %d node(s) from the trashcan (permanently deleted)".formatted(archive.purgeAllArchivedNodes());
        } catch (StatusRuntimeException e) {
            throw error(e);
        }
    }

}

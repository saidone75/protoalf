package org.saidone.protoalf.mcp;

import io.grpc.StatusRuntimeException;
import lombok.RequiredArgsConstructor;
import org.saidone.protoalf.client.NodeServiceClient;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.util.Map;

import static org.saidone.protoalf.mcp.ToolSupport.*;

/** MCP tools backed by NodeServiceClient. */
@RequiredArgsConstructor
public class NodeServiceTools {

    private final NodeServiceClient nodes;

    @Tool(name = "alfresco_get_node", description = """
        Reads a node (document or folder) from the Alfresco repository: name, type, aspects and all properties. \
        Accepts a nodeRef, a node id or an alias (-root-, -my-, -shared-).""")
    public String getNode(@ToolParam(description = NODE_PARAM) String nodeRef) {
        try {
            return json(nodes.getNode(nodeRef));
        } catch (StatusRuntimeException e) {
            throw error(e);
        }
    }

    @Tool(name = "alfresco_update_node", description = """
        Renames a node, changes its type and/or updates its metadata, then returns it. Only the given \
        properties change; properties of aspects (e.g. cm:title) add the aspect automatically. \
        The type change is applied before the properties, so properties of the new type can be set \
        in the same call. Use alfresco_list_models to find the types and their properties.""")
    public String updateNode(@ToolParam(description = NODE_PARAM) String nodeRef, @ToolParam(description = "New name, omit to keep the current one", required = false) String name, @ToolParam(description = """
        New type, e.g. a custom document type; omit to keep the current one. A document must stay a \
        cm:content subtype and a folder a cm:folder subtype""", required = false) String type, @ToolParam(description = PROPERTIES_PARAM, required = false) Map<String, String> properties) {
        return run(() -> nodes.updateNode(nodeRef, text(name), text(type), map(properties)));
    }

    @Tool(name = "alfresco_delete_node", description = """
        Deletes a document or folder (with all its content) by moving it to the Alfresco trashcan, \
        from where an administrator can restore it. Ask the user for confirmation before deleting.""")
    public String deleteNode(@ToolParam(description = NODE_PARAM) String nodeRef) {
        try {
            nodes.deleteNode(nodeRef);
            return "Deleted %s (moved to the trashcan)".formatted(nodeRef);
        } catch (StatusRuntimeException e) {
            throw error(e);
        }
    }

}

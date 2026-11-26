package org.saidone.protoalf.mcp;

import io.grpc.StatusRuntimeException;
import lombok.RequiredArgsConstructor;
import lombok.val;
import org.saidone.protoalf.client.FileFolderServiceClient;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.util.Map;

import static org.saidone.protoalf.mcp.ToolSupport.*;

/** MCP tools backed by FileFolderServiceClient. */
@RequiredArgsConstructor
public class FileFolderServiceTools {

    private final FileFolderServiceClient files;

    @Tool(name = "alfresco_list_children", description = """
        Lists the files and folders contained in an Alfresco folder, sorted by name, with paging. \
        Start from -root- (Company Home), -my- (user home) or -shared- (Shared Files). \
        'totalItems' is the number of children (-1 if unknown); while 'hasMoreItems' is true, \
        increase skipCount by the page size to read the next page.""")
    public String listChildren(@ToolParam(description = FOLDER_PARAM) String nodeRef, @ToolParam(description = "Number of items to skip (paging offset), 0 for the first page", required = false) Integer skipCount, @ToolParam(description = "Page size, 1..1000 (default 100)", required = false) Integer maxItems) {
        try {
            return json(files.list(nodeRef, skipCount == null ? 0 : skipCount, maxItems == null ? 100 : maxItems));
        } catch (StatusRuntimeException e) {
            throw error(e);
        }
    }

    @Tool(name = "alfresco_create_folder", description = """
        Creates a folder in Alfresco and returns it. Fails with ALREADY_EXISTS if the parent \
        already contains a node with the same name.""")
    public String createFolder(@ToolParam(description = "Parent folder: a nodeRef, a bare node id or an alias (-root-, -my-, -shared-)") String parentRef, @ToolParam(description = "Folder name") String name, @ToolParam(description = PROPERTIES_PARAM, required = false) Map<String, PropertyInput> properties) {
        return run(() -> files.create(parentRef, text(name), "cm:folder", map(properties), null));
    }

    @Tool(name = "alfresco_create_document", description = """
        Creates a document in an Alfresco folder and returns it. Pass text content in 'content', \
        or binary content base64-encoded in 'contentBase64' (not both). The mimetype is guessed from \
        the name extension unless given. Fails with ALREADY_EXISTS if the name is taken.""")
    public String createDocument(@ToolParam(description = "Parent folder: a nodeRef, a bare node id or an alias (-root-, -my-, -shared-)") String parentRef, @ToolParam(description = "Document name including the extension, e.g. notes.md") String name, @ToolParam(description = "Text content (UTF-8)", required = false) String content, @ToolParam(description = "Binary content encoded in base64", required = false) String contentBase64, @ToolParam(description = "Mimetype, e.g. text/markdown; guessed from the name when omitted", required = false) String mimetype, @ToolParam(description = PROPERTIES_PARAM, required = false) Map<String, PropertyInput> properties) {
        val body = content(content, contentBase64, mimetype, false);
        return run(() -> files.create(parentRef, text(name), "cm:content", map(properties), body));
    }

    @Tool(name = "alfresco_move_node", description = "Moves a document or folder into another folder, optionally renaming it, and returns it.")
    public String moveNode(@ToolParam(description = NODE_PARAM) String nodeRef, @ToolParam(description = TARGET_PARAM) String targetParentRef, @ToolParam(description = "New name, omit to keep the current one", required = false) String name) {
        return run(() -> files.move(nodeRef, targetParentRef, text(name)));
    }

    @Tool(name = "alfresco_copy_node", description = """
        Copies a document or folder (with all its content) into a folder, optionally with a new name, \
        and returns the copy.""")
    public String copyNode(@ToolParam(description = NODE_PARAM) String nodeRef, @ToolParam(description = TARGET_PARAM) String targetParentRef, @ToolParam(description = "Name of the copy, omit to keep the original name", required = false) String name) {
        return run(() -> files.copy(nodeRef, targetParentRef, text(name)));
    }

}

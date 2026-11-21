package org.saidone.protoalf.mcp;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.google.protobuf.ByteString;
import com.google.protobuf.Descriptors;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.MessageOrBuilder;
import com.google.protobuf.util.JsonFormat;
import io.grpc.StatusRuntimeException;
import lombok.val;
import org.saidone.protoalf.api.Content;
import org.saidone.protoalf.api.GetContentResponse;
import org.saidone.protoalf.api.ListChildrenResponse;
import org.saidone.protoalf.api.PropertyInfo;
import org.saidone.protoalf.api.SearchResponse;
import org.saidone.protoalf.api.SortField;
import org.saidone.protoalf.client.NodeServiceClient;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import tools.jackson.databind.json.JsonMapper;

import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * Alfresco operations exposed to the LLM as MCP tools.
 * Results are returned as JSON (protobuf JSON mapping). gRPC errors are rethrown as {@link AlfrescoToolException}
 * with a readable message: the MCP server returns it to the client as a tool error ({@code isError: true}),
 * so the model can correct its arguments instead of failing the whole conversation.
 */
public class AlfrescoTools {

    /**
     * Proto3 JSON omits fields holding their default value: without this, an empty page would have no "entries"
     * and the last page no "hasMoreItems" (false). Paging fields are always printed so the model can rely on them;
     * node fields keep the compact default mapping.
     */
    private static final JsonFormat.Printer JSON = JsonFormat.printer().omittingInsignificantWhitespace()
            .includingDefaultValueFields(java.util.Set.of(
                    field(ListChildrenResponse.getDescriptor(), ListChildrenResponse.ENTRIES_FIELD_NUMBER),
                    field(ListChildrenResponse.getDescriptor(), ListChildrenResponse.HAS_MORE_ITEMS_FIELD_NUMBER),
                    field(ListChildrenResponse.getDescriptor(), ListChildrenResponse.TOTAL_ITEMS_FIELD_NUMBER),
                    field(SearchResponse.getDescriptor(), SearchResponse.ENTRIES_FIELD_NUMBER),
                    field(SearchResponse.getDescriptor(), SearchResponse.HAS_MORE_ITEMS_FIELD_NUMBER),
                    field(SearchResponse.getDescriptor(), SearchResponse.TOTAL_ITEMS_FIELD_NUMBER),
                    field(PropertyInfo.getDescriptor(), PropertyInfo.MANDATORY_FIELD_NUMBER),
                    field(PropertyInfo.getDescriptor(), PropertyInfo.MULTI_VALUED_FIELD_NUMBER),
                    field(PropertyInfo.getDescriptor(), PropertyInfo.READ_ONLY_FIELD_NUMBER)));

    private static Descriptors.FieldDescriptor field(Descriptors.Descriptor message, int number) {
        return message.findFieldByNumber(number);
    }

    private static final String NODE_PARAM = """
            Node to read: a nodeRef (workspace://SpacesStore/<id>), a bare node id, \
            or one of the aliases -root- (Company Home), -my- (user home folder), -shared- (Shared Files)""";
    private static final String FOLDER_PARAM = """
            Folder to list: a nodeRef (workspace://SpacesStore/<id>), a bare node id, \
            or one of the aliases -root- (Company Home), -my- (user home folder), -shared- (Shared Files)""";

    private final NodeServiceClient client;

    public AlfrescoTools(NodeServiceClient client) {
        this.client = client;
    }

    @Tool(name = "alfresco_get_node",
            description = """
                    Reads a node (document or folder) from the Alfresco repository: name, type, aspects and all properties. \
                    Accepts a nodeRef, a node id or an alias (-root-, -my-, -shared-).""")
    public String getNode(
            @ToolParam(description = NODE_PARAM) String nodeRef) {
        try {
            return json(client.getNode(nodeRef));
        } catch (StatusRuntimeException e) {
            throw error(e);
        }
    }

    @Tool(name = "alfresco_list_children",
            description = """
                    Lists the files and folders contained in an Alfresco folder, sorted by name, with paging. \
                    Start from -root- (Company Home), -my- (user home) or -shared- (Shared Files). \
                    'totalItems' is the number of children (-1 if unknown); while 'hasMoreItems' is true, \
                    increase skipCount by the page size to read the next page.""")
    public String listChildren(
            @ToolParam(description = FOLDER_PARAM) String nodeRef,
            @ToolParam(description = "Number of items to skip (paging offset), 0 for the first page", required = false) Integer skipCount,
            @ToolParam(description = "Page size, 1..1000 (default 100)", required = false) Integer maxItems) {
        try {
            return json(client.listChildren(nodeRef,
                    skipCount == null ? 0 : skipCount,
                    maxItems == null ? 100 : maxItems));
        } catch (StatusRuntimeException e) {
            throw error(e);
        }
    }

    @Tool(name = "alfresco_search",
            description = """
                    Searches the Alfresco repository with an AFTS (Alfresco Full Text Search) query and returns the \
                    matching documents and folders with their metadata. Examples: \
                    'budget' (name, title, description and content), \
                    'cm:name:"report*"' (name starting with report), \
                    'TEXT:"invoice 2024"' (content), \
                    'TYPE:"cm:folder" AND cm:name:"projects"', \
                    'TYPE:"cm:content" AND cm:modified:[NOW-7DAYS TO NOW]', \
                    'PATH:"/app:company_home/cm:Projects//*"' (everything below a folder), \
                    'cm:creator:"jdoe"'. \
                    Combine clauses with AND, OR, NOT and parentheses. \
                    Results come from the search index, which may lag a few seconds behind recent changes. \
                    'totalItems' is the number of matches; check 'hasMoreItems' and increase skipCount for the next page. \
                    Results are sorted by relevance unless 'sort' is given.""")
    public String search(
            @ToolParam(description = "AFTS query, e.g. cm:name:\"budget*\" AND TYPE:\"cm:content\"") String query,
            @ToolParam(description = "Number of results to skip (paging offset), 0 for the first page", required = false) Integer skipCount,
            @ToolParam(description = "Page size, 1..1000 (default 25)", required = false) Integer maxItems,
            @ToolParam(description = """
                    Sort order: comma-separated 'field [asc|desc]', applied in order (ascending by default). \
                    field is a property (e.g. cm:name, cm:modified, cm:created) or score (relevance). \
                    Example: 'cm:modified desc, cm:name'""", required = false) String sort) {
        try {
            return json(client.search(query, "afts",
                    skipCount == null ? 0 : skipCount,
                    maxItems == null ? 25 : maxItems,
                    sort(sort)));
        } catch (StatusRuntimeException e) {
            throw error(e);
        }
    }

    @Tool(name = "alfresco_list_models",
            description = """
                    Lists the content models of the Alfresco data dictionary with their types and aspects \
                    (name, title, parent, mandatory aspects). Use it to discover which types exist (e.g. for \
                    TYPE:"..." in alfresco_search) and, with includeProperties, which metadata properties a type \
                    or aspect accepts and their data types (for the properties of the create/update tools). \
                    Without 'model' it lists every model, without properties. With 'model' (a namespace prefix such \
                    as cm, or a model name such as cm:contentmodel) it lists that model only. Properties are those \
                    a type/aspect defines itself: inherited ones are on its parent. readOnly properties cannot be set.""")
    public String listModels(
            @ToolParam(description = "Namespace prefix (e.g. cm) or model name (e.g. cm:contentmodel); omit for every model", required = false) String model,
            @ToolParam(description = "Also list the properties of each type and aspect; requires model", required = false) Boolean includeProperties) {
        return run(() -> client.listModels(text(model), Boolean.TRUE.equals(includeProperties)));
    }

    static final int DEFAULT_READ_BYTES = 100_000;

    @Tool(name = "alfresco_read_content",
            description = """
                    Reads the content of an Alfresco document. By default returns its plain text ('text'), extracted \
                    by Alfresco from PDF, Office, HTML and the other formats it can convert; with binary=true returns \
                    the stored bytes base64-encoded ('contentBase64'). Long content comes in slices of at most \
                    maxBytes bytes: while 'hasMore' is true, call again with offset = 'nextOffset'. 'size' is the \
                    total size in bytes (of the extracted text in text mode). FAILED_PRECONDITION means the document \
                    format cannot be converted to text.""")
    public String readContent(
            @ToolParam(description = NODE_PARAM) String nodeRef,
            @ToolParam(description = "Return the stored bytes base64-encoded instead of the extracted text (default false)", required = false) Boolean binary,
            @ToolParam(description = "Byte offset to start from: 0 for the beginning, then the nextOffset of the previous slice", required = false) Long offset,
            @ToolParam(description = "Slice size in bytes, 1..2097152 (default 100000)", required = false) Integer maxBytes) {
        val raw = Boolean.TRUE.equals(binary);
        val start = offset == null ? 0 : offset;
        GetContentResponse response;
        try {
            response = client.getContent(nodeRef, !raw, start, maxBytes == null ? DEFAULT_READ_BYTES : maxBytes);
        } catch (StatusRuntimeException e) {
            throw error(e);
        }
        val data = response.getContent().getData();
        val hasMore = response.getHasMore();
        return SLICES.writeValueAsString(new ContentSlice(nodeRef, response.getContent().getMimetype(), response.getSize(),
                start, hasMore, hasMore ? start + data.size() : null,
                raw ? null : data.toStringUtf8(), raw ? Base64.getEncoder().encodeToString(data.toByteArray()) : null));
    }

    private static final JsonMapper SLICES = JsonMapper.builder().build();

    /**
     * One slice of a document content, as returned by alfresco_read_content. Null fields are omitted.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record ContentSlice(String nodeRef, String mimetype, long size, long offset, boolean hasMore, Long nextOffset,
                        String text, String contentBase64) {
    }

    // ---- Write tools: run as the configured Alfresco user, with its permissions ----

    private static final String PROPERTIES_PARAM = """
            Optional metadata as an object of property name -> text value, e.g. \
            {"cm:title": "Q3 budget", "cm:description": "Draft", "cm:author": "J. Doe"}. \
            Dates in ISO 8601 (2026-11-02T10:00:00Z). The name is set with 'name', never as cm:name.""";
    private static final String TARGET_PARAM = """
            Destination folder: a nodeRef, a bare node id or an alias (-root-, -my-, -shared-)""";

    @Tool(name = "alfresco_create_folder",
            description = """
                    Creates a folder in Alfresco and returns it. Fails with ALREADY_EXISTS if the parent \
                    already contains a node with the same name.""")
    public String createFolder(
            @ToolParam(description = "Parent folder: a nodeRef, a bare node id or an alias (-root-, -my-, -shared-)") String parentRef,
            @ToolParam(description = "Folder name") String name,
            @ToolParam(description = PROPERTIES_PARAM, required = false) Map<String, String> properties) {
        return run(() -> client.createNode(parentRef, text(name), "cm:folder", map(properties), null));
    }

    @Tool(name = "alfresco_create_document",
            description = """
                    Creates a document in an Alfresco folder and returns it. Pass text content in 'content', \
                    or binary content base64-encoded in 'contentBase64' (not both). The mimetype is guessed from \
                    the name extension unless given. Fails with ALREADY_EXISTS if the name is taken.""")
    public String createDocument(
            @ToolParam(description = "Parent folder: a nodeRef, a bare node id or an alias (-root-, -my-, -shared-)") String parentRef,
            @ToolParam(description = "Document name including the extension, e.g. notes.md") String name,
            @ToolParam(description = "Text content (UTF-8)", required = false) String content,
            @ToolParam(description = "Binary content encoded in base64", required = false) String contentBase64,
            @ToolParam(description = "Mimetype, e.g. text/markdown; guessed from the name when omitted", required = false) String mimetype,
            @ToolParam(description = PROPERTIES_PARAM, required = false) Map<String, String> properties) {
        val body = content(content, contentBase64, mimetype, false);
        return run(() -> client.createNode(parentRef, text(name), "cm:content", map(properties), body));
    }

    @Tool(name = "alfresco_update_node",
            description = """
                    Renames a node, changes its type and/or updates its metadata, then returns it. Only the given \
                    properties change; properties of aspects (e.g. cm:title) add the aspect automatically. \
                    The type change is applied before the properties, so properties of the new type can be set \
                    in the same call. Use alfresco_list_models to find the types and their properties.""")
    public String updateNode(
            @ToolParam(description = NODE_PARAM) String nodeRef,
            @ToolParam(description = "New name, omit to keep the current one", required = false) String name,
            @ToolParam(description = """
                    New type, e.g. a custom document type; omit to keep the current one. A document must stay a \
                    cm:content subtype and a folder a cm:folder subtype""", required = false) String type,
            @ToolParam(description = PROPERTIES_PARAM, required = false) Map<String, String> properties) {
        return run(() -> client.updateNode(nodeRef, text(name), text(type), map(properties)));
    }

    @Tool(name = "alfresco_update_content",
            description = """
                    Replaces the content of an Alfresco document with text ('content') or base64 binary \
                    ('contentBase64'), then returns the document.""")
    public String updateContent(
            @ToolParam(description = NODE_PARAM) String nodeRef,
            @ToolParam(description = "New text content (UTF-8)", required = false) String content,
            @ToolParam(description = "New binary content encoded in base64", required = false) String contentBase64,
            @ToolParam(description = "Mimetype; guessed from the document name when omitted", required = false) String mimetype) {
        val body = content(content, contentBase64, mimetype, true);
        return run(() -> client.updateContent(nodeRef, body));
    }

    @Tool(name = "alfresco_move_node",
            description = "Moves a document or folder into another folder, optionally renaming it, and returns it.")
    public String moveNode(
            @ToolParam(description = NODE_PARAM) String nodeRef,
            @ToolParam(description = TARGET_PARAM) String targetParentRef,
            @ToolParam(description = "New name, omit to keep the current one", required = false) String name) {
        return run(() -> client.moveNode(nodeRef, targetParentRef, text(name)));
    }

    @Tool(name = "alfresco_copy_node",
            description = """
                    Copies a document or folder (with all its content) into a folder, optionally with a new name, \
                    and returns the copy.""")
    public String copyNode(
            @ToolParam(description = NODE_PARAM) String nodeRef,
            @ToolParam(description = TARGET_PARAM) String targetParentRef,
            @ToolParam(description = "Name of the copy, omit to keep the original name", required = false) String name) {
        return run(() -> client.copyNode(nodeRef, targetParentRef, text(name)));
    }

    @Tool(name = "alfresco_delete_node",
            description = """
                    Deletes a document or folder (with all its content) by moving it to the Alfresco trashcan, \
                    from where an administrator can restore it. Ask the user for confirmation before deleting.""")
    public String deleteNode(@ToolParam(description = NODE_PARAM) String nodeRef) {
        try {
            client.deleteNode(nodeRef);
            return "Deleted %s (moved to the trashcan)".formatted(nodeRef);
        } catch (StatusRuntimeException e) {
            throw error(e);
        }
    }

    /**
     * Line breaks are common in base64 produced by models and tools; anything else invalid is rejected.
     */
    private static final java.util.regex.Pattern WHITESPACE = java.util.regex.Pattern.compile("\\s+");

    private static String run(java.util.function.Supplier<? extends MessageOrBuilder> call) {
        try {
            return json(call.get());
        } catch (StatusRuntimeException e) {
            throw error(e);
        }
    }

    private static String text(String value) {
        return value == null ? "" : value;
    }

    private static Map<String, String> map(Map<String, String> value) {
        return value == null ? Map.of() : value;
    }

    /**
     * Parses the sort parameter of alfresco_search: "field [asc|desc], ..."; empty when not given.
     */
    static List<SortField> sort(String value) {
        if (value == null || value.isBlank()) return List.of();
        val result = new java.util.ArrayList<SortField>();
        for (val criterion : value.split(",", -1)) {
            val parts = WHITESPACE.split(criterion.strip());
            val valid = !parts[0].isEmpty() && parts.length <= 2
                    && (parts.length == 1 || parts[1].equalsIgnoreCase("asc") || parts[1].equalsIgnoreCase("desc"));
            if (!valid)
                throw new AlfrescoToolException("Invalid sort '%s': use 'field [asc|desc]', comma-separated".formatted(criterion.strip()));
            result.add(SortField.newBuilder().setField(parts[0])
                    .setDescending(parts.length == 2 && parts[1].equalsIgnoreCase("desc")).build());
        }
        return result;
    }

    /**
     * Builds the request content from text or base64; null when neither is given and content is optional.
     */
    static Content content(String text, String base64, String mimetype, boolean required) {
        val hasText = text != null;
        val hasBinary = base64 != null && !base64.isEmpty();
        if (hasText && hasBinary) throw new AlfrescoToolException("Pass either content or contentBase64, not both");
        if (!hasText && !hasBinary) {
            if (required) throw new AlfrescoToolException("content or contentBase64 is required");
            return null;
        }
        ByteString data;
        try {
            data = hasText ? ByteString.copyFromUtf8(text) : ByteString.copyFrom(Base64.getDecoder().decode(WHITESPACE.matcher(base64).replaceAll("")));
        } catch (IllegalArgumentException e) {
            throw new AlfrescoToolException("contentBase64 is not valid base64");
        }
        return Content.newBuilder().setData(data).setMimetype(text(mimetype))
                .setEncoding(hasText ? "UTF-8" : "").build();
    }

    private static String json(MessageOrBuilder message) {
        try {
            return JSON.print(message);
        } catch (InvalidProtocolBufferException e) {
            throw new IllegalStateException("Cannot render Alfresco response as JSON", e);
        }
    }

    private static AlfrescoToolException error(StatusRuntimeException e) {
        val description = e.getStatus().getDescription();
        val message = description == null
                ? "Alfresco error %s".formatted(e.getStatus().getCode())
                : "Alfresco error %s: %s".formatted(e.getStatus().getCode(), description);
        return new AlfrescoToolException(message, e);
    }

    /**
     * A failed Alfresco call, reported to the MCP client as a tool error.
     */
    public static final class AlfrescoToolException extends RuntimeException {
        AlfrescoToolException(String message, Throwable cause) {
            super(message, cause);
        }

        AlfrescoToolException(String message) {
            super(message);
        }
    }
}

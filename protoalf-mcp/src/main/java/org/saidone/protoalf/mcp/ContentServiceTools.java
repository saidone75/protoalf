package org.saidone.protoalf.mcp;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.grpc.StatusRuntimeException;
import lombok.RequiredArgsConstructor;
import lombok.val;
import org.saidone.protoalf.api.GetContentResponse;
import org.saidone.protoalf.client.ContentServiceClient;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import tools.jackson.databind.json.JsonMapper;

import java.util.Base64;

import static org.saidone.protoalf.mcp.ToolSupport.*;

/** MCP tools backed by ContentServiceClient. */
@RequiredArgsConstructor
public class ContentServiceTools {

    private final ContentServiceClient contents;
    static final int DEFAULT_READ_BYTES = 100_000;
    private static final JsonMapper SLICES = JsonMapper.builder().build();

    @Tool(name = "alfresco_read_content", description = """
        Reads the content of an Alfresco document. By default returns its plain text ('text'), extracted \
        by Alfresco from PDF, Office, HTML and the other formats it can convert; with binary=true returns \
        the stored bytes base64-encoded ('contentBase64'). Long content comes in slices of at most \
        maxBytes bytes: while 'hasMore' is true, call again with offset = 'nextOffset'. 'size' is the \
        total size in bytes (of the extracted text in text mode). FAILED_PRECONDITION means the document \
        format cannot be converted to text.""")
    public String readContent(@ToolParam(description = NODE_PARAM) String nodeRef, @ToolParam(description = "Return the stored bytes base64-encoded instead of the extracted text (default false)", required = false) Boolean binary, @ToolParam(description = "Byte offset to start from: 0 for the beginning, then the nextOffset of the previous slice", required = false) Long offset, @ToolParam(description = "Slice size in bytes, 1..2097152 (default 100000)", required = false) Integer maxBytes) {
        val raw = Boolean.TRUE.equals(binary);
        val start = offset == null ? 0 : offset;
        GetContentResponse response;
        try {
            response = contents.getReader(nodeRef, !raw, start, maxBytes == null ? DEFAULT_READ_BYTES : maxBytes);
        } catch (StatusRuntimeException e) {
            throw error(e);
        }
        val data = response.getContent().getData();
        val hasMore = response.getHasMore();
        return SLICES.writeValueAsString(new ContentSlice(nodeRef, response.getContent().getMimetype(), response.getSize(), start, hasMore, hasMore ? start + data.size() : null, raw ? null : data.toStringUtf8(), raw ? Base64.getEncoder().encodeToString(data.toByteArray()) : null));
    }

    @Tool(name = "alfresco_update_content", description = """
        Replaces the content of an Alfresco document with text ('content') or base64 binary \
        ('contentBase64'), then returns the document.""")
    public String updateContent(@ToolParam(description = NODE_PARAM) String nodeRef, @ToolParam(description = "New text content (UTF-8)", required = false) String content, @ToolParam(description = "New binary content encoded in base64", required = false) String contentBase64, @ToolParam(description = "Mimetype; guessed from the document name when omitted", required = false) String mimetype) {
        val body = content(content, contentBase64, mimetype, true);
        return run(() -> contents.getWriter(nodeRef, body));
    }

    /**
     * One slice of a document content, as returned by alfresco_read_content. Null fields are omitted.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record ContentSlice(String nodeRef, String mimetype, long size, long offset, boolean hasMore, Long nextOffset, String text, String contentBase64) {
    }

}

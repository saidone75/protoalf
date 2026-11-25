package org.saidone.protoalf.mcp;

import com.google.protobuf.ByteString;
import com.google.protobuf.Descriptors;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.MessageOrBuilder;
import com.google.protobuf.util.JsonFormat;
import io.grpc.StatusRuntimeException;
import lombok.val;
import org.saidone.protoalf.api.Content;
import org.saidone.protoalf.api.ListChildrenResponse;
import org.saidone.protoalf.api.PropertyInfo;
import org.saidone.protoalf.api.SearchResponse;

import java.util.Base64;
import java.util.Map;
import java.util.HashMap;
import org.saidone.protoalf.api.Property;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/** Shared argument descriptions, JSON mapping and error handling for MCP tools. */
final class ToolSupport {

    private ToolSupport() {}

    /**
     * Proto3 JSON omits fields holding their default value: without this, an empty page would have no "entries"
     * and the last page no "hasMoreItems" (false). Paging fields are always printed so the model can rely on
     * them; node fields keep the compact default mapping.
     */
    private static final JsonFormat.Printer JSON = JsonFormat.printer().omittingInsignificantWhitespace().includingDefaultValueFields(Set.of(field(ListChildrenResponse.getDescriptor(), ListChildrenResponse.ENTRIES_FIELD_NUMBER), field(ListChildrenResponse.getDescriptor(), ListChildrenResponse.HAS_MORE_ITEMS_FIELD_NUMBER), field(ListChildrenResponse.getDescriptor(), ListChildrenResponse.TOTAL_ITEMS_FIELD_NUMBER), field(SearchResponse.getDescriptor(), SearchResponse.ENTRIES_FIELD_NUMBER), field(SearchResponse.getDescriptor(), SearchResponse.HAS_MORE_ITEMS_FIELD_NUMBER), field(SearchResponse.getDescriptor(), SearchResponse.TOTAL_ITEMS_FIELD_NUMBER), field(PropertyInfo.getDescriptor(), PropertyInfo.MANDATORY_FIELD_NUMBER), field(PropertyInfo.getDescriptor(), PropertyInfo.MULTI_VALUED_FIELD_NUMBER), field(PropertyInfo.getDescriptor(), PropertyInfo.READ_ONLY_FIELD_NUMBER)));

    static final String NODE_PARAM = """
        Node to read: a nodeRef (workspace://SpacesStore/<id>), a bare node id, \
        or one of the aliases -root- (Company Home), -my- (user home folder), -shared- (Shared Files)""";

    static final String FOLDER_PARAM = """
        Folder to list: a nodeRef (workspace://SpacesStore/<id>), a bare node id, \
        or one of the aliases -root- (Company Home), -my- (user home folder), -shared- (Shared Files)""";
    static final String PROPERTIES_PARAM = """
        Optional typed metadata keyed by property name, e.g. \
        {"cm:title":{"multiValued":false,"values":[{"stringValue":"Budget"}]}}. \
        For lists use multiValued:true and zero or more typed values. Empty lists clear lists; \
        {"multiValued":false,"values":[{"nullValue":true}]} clears a scalar. Omitted properties are unchanged. \
        Each value has exactly one field: stringValue, integerValue, doubleValue, booleanValue, \
        timestampMillis (epoch milliseconds), nodeRefValue or qnameValue. Types and cardinality must \
        match the dictionary. Set the name with 'name', never as cm:name.""";

    static final String TARGET_PARAM = """
        Destination folder: a nodeRef, a bare node id or an alias (-root-, -my-, -shared-)""";

    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    /**
     * Builds the request content from text or base64; null when neither is given and content is optional.
     */
    static Content content(String text, String base64, String mimetype, boolean required) {
        val hasText = text != null;
        val hasBinary = base64 != null && !base64.isEmpty();
        if (hasText && hasBinary)
            throw new AlfrescoToolException("Pass either content or contentBase64, not both");
        if (!hasText && !hasBinary) {
            if (required)
                throw new AlfrescoToolException("content or contentBase64 is required");
            return null;
        }
        ByteString data;
        try {
            data = hasText ? ByteString.copyFromUtf8(text) : ByteString.copyFrom(Base64.getDecoder().decode(WHITESPACE.matcher(base64).replaceAll("")));
        } catch (IllegalArgumentException e) {
            throw new AlfrescoToolException("contentBase64 is not valid base64");
        }
        return Content.newBuilder().setData(data).setMimetype(text(mimetype)).setEncoding(hasText ? "UTF-8" : "").build();
    }

    private static Descriptors.FieldDescriptor field(Descriptors.Descriptor message, int number) {
        return message.findFieldByNumber(number);
    }

    static String run(Supplier<? extends MessageOrBuilder> call) {
        try {
            return json(call.get());
        } catch (StatusRuntimeException e) {
            throw error(e);
        }
    }

    static String text(String value) {
        return value == null ? "" : value;
    }

    static Map<String, Property> map(Map<String, PropertyInput> value) {
        if (value == null) return Map.of();
        var result = new HashMap<String, Property>();
        value.forEach((name, property) -> {
            if (property == null) throw new AlfrescoToolException("Use an explicit nullValue for " + name);
            result.put(name, property.toProto());
        });
        return result;
    }

    static String json(MessageOrBuilder message) {
        try {
            return JSON.print(message);
        } catch (InvalidProtocolBufferException e) {
            throw new IllegalStateException("Cannot render Alfresco response as JSON", e);
        }
    }

    static AlfrescoToolException error(StatusRuntimeException e) {
        val description = e.getStatus().getDescription();
        val message = description == null ? "Alfresco error %s".formatted(e.getStatus().getCode()) : "Alfresco error %s: %s".formatted(e.getStatus().getCode(), description);
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

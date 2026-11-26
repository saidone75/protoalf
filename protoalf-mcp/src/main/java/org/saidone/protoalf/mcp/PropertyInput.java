package org.saidone.protoalf.mcp;

import org.saidone.protoalf.api.Property;
import org.saidone.protoalf.api.PropertyValue;
import org.springframework.ai.tool.annotation.ToolParam;

import java.util.List;

/** MCP input schema for the same explicit scalar/list values used by the gRPC contract. */
public record PropertyInput(
        @ToolParam(description = "true for a list, false for a scalar; must match the Alfresco dictionary") boolean multiValued,
        @ToolParam(description = "Exactly one value for a scalar, zero or more for a list; empty lists clear the property") List<Value> values) {

    Property toProto() {
        if (values == null || (!multiValued && values.size() != 1))
            throw new ToolSupport.AlfrescoToolException("A scalar requires exactly one value; a list requires an explicit values array");
        var result = Property.newBuilder().setMultiValued(multiValued);
        for (var value : values) {
            if (value == null || (multiValued && value.nullValue() != null))
                throw new ToolSupport.AlfrescoToolException("List elements cannot be null; use an empty list to clear");
            result.addValues(value.toProto());
        }
        return result.build();
    }

    /** Exactly one field must be supplied, mirroring PropertyValue's Protobuf oneof. */
    public record Value(
            @ToolParam(description = "Text value", required = false) String stringValue,
            @ToolParam(description = "Integral value", required = false) Long integerValue,
            @ToolParam(description = "Finite floating-point value", required = false) Double doubleValue,
            @ToolParam(description = "Boolean value", required = false) Boolean booleanValue,
            @ToolParam(description = "Timestamp as milliseconds since the Unix epoch", required = false) Long timestampMillis,
            @ToolParam(description = "Full Alfresco node reference", required = false) String nodeRefValue,
            @ToolParam(description = "Qualified name, prefix:name or {uri}name", required = false) String qnameValue,
            @ToolParam(description = "true to clear a scalar property", required = false) Boolean nullValue) {

        PropertyValue toProto() {
            int count = 0;
            var result = PropertyValue.newBuilder();
            if (stringValue != null) { result.setStringValue(stringValue); count++; }
            if (integerValue != null) { result.setIntegerValue(integerValue); count++; }
            if (doubleValue != null) {
                if (!Double.isFinite(doubleValue)) throw new ToolSupport.AlfrescoToolException("Numbers must be finite");
                result.setDoubleValue(doubleValue); count++;
            }
            if (booleanValue != null) { result.setBooleanValue(booleanValue); count++; }
            if (timestampMillis != null) { result.setTimestampMillis(timestampMillis); count++; }
            if (nodeRefValue != null) { result.setNodeRefValue(nodeRefValue); count++; }
            if (qnameValue != null) { result.setQnameValue(qnameValue); count++; }
            if (nullValue != null) {
                if (!nullValue) throw new ToolSupport.AlfrescoToolException("nullValue must be true");
                result.setNullValue(true); count++;
            }
            if (count != 1) throw new ToolSupport.AlfrescoToolException("Each property value must specify exactly one typed field");
            return result.build();
        }
    }
}

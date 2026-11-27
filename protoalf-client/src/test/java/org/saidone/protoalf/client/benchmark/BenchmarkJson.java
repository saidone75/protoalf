package org.saidone.protoalf.client.benchmark;

import com.google.protobuf.ListValue;
import com.google.protobuf.Struct;
import com.google.protobuf.Value;
import com.google.protobuf.util.JsonFormat;

import java.io.IOException;
import java.util.Map;

final class BenchmarkJson {
    private BenchmarkJson() {}
    static Struct parse(String json) throws IOException {
        var result = Struct.newBuilder();
        JsonFormat.parser().merge(json, result);
        return result.build();
    }
    static String print(Map<String, ?> map) throws IOException {
        return JsonFormat.printer().print(object(map));
    }
    static Struct object(Map<?, ?> map) {
        var result = Struct.newBuilder();
        map.forEach((key, value) -> result.putFields(key.toString(), value(value)));
        return result.build();
    }
    static Value value(Object value) {
        var result = Value.newBuilder();
        if (value instanceof Map<?, ?> map) result.setStructValue(object(map));
        else if (value instanceof Iterable<?> list) {
            var array = ListValue.newBuilder();
            list.forEach(item -> array.addValues(value(item)));
            result.setListValue(array);
        } else if (value instanceof Number number) result.setNumberValue(number.doubleValue());
        else if (value instanceof Boolean bool) result.setBoolValue(bool);
        else result.setStringValue(String.valueOf(value));
        return result.build();
    }
    static Value field(Struct object, String key) throws IOException {
        if (!object.containsFields(key)) throw new IOException("Missing JSON field: " + key);
        return object.getFieldsOrThrow(key);
    }
    static Struct child(Struct object, String key) throws IOException { return field(object, key).getStructValue(); }
    static String text(Struct object, String key) throws IOException { return field(object, key).getStringValue(); }
}

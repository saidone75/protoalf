package org.saidone.protoalf.client;

import org.saidone.protoalf.api.Property;
import org.saidone.protoalf.api.PropertyValue;

import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;
import java.util.function.Function;

/**
 * Typed property factories shared by all write clients. Singular factories create scalars; plural factories
 * create explicit lists, including singleton and empty lists. Use {@link #nullValue()} to clear a scalar and
 * an empty plural factory to clear a list. Omit a property to preserve it on update.
 *
 * <p>The repository validates value kinds and cardinality against its dictionary; scalars and lists are never
 * implicitly interchanged. These factories do not depend on Alfresco repository classes.
 */
public final class Properties {

    private Properties() {}

    public static Property text(String value) { return scalar(string(value)); }
    public static Property texts(String... values) { return list(values, Properties::string); }
    public static Property integer(long value) { return scalar(integral(value)); }
    public static Property integers(long... values) { return list(Arrays.stream(values).boxed().toArray(Long[]::new), Properties::integral); }
    public static Property decimal(double value) { return scalar(floating(value)); }
    public static Property decimals(double... values) { return list(Arrays.stream(values).boxed().toArray(Double[]::new), Properties::floating); }
    public static Property bool(boolean value) { return scalar(booleanValue(value)); }
    public static Property booleans(Boolean... values) { return list(values, Properties::booleanValue); }
    public static Property timestamp(Instant value) { return scalar(instant(value)); }
    public static Property timestamps(Instant... values) { return list(values, Properties::instant); }
    public static Property nodeRef(String value) { return scalar(reference(value)); }
    public static Property nodeRefs(String... values) { return list(values, Properties::reference); }
    public static Property qname(String value) { return scalar(qualifiedName(value)); }
    public static Property qnames(String... values) { return list(values, Properties::qualifiedName); }

    /** Explicit null for a single-valued property; list properties use an empty list instead. */
    public static Property nullValue() {
        return scalar(PropertyValue.newBuilder().setNullValue(true).build());
    }

    private static Property scalar(PropertyValue value) {
        return Property.newBuilder().addValues(value).build();
    }

    private static <T> Property list(T[] values, Function<T, PropertyValue> convert) {
        var property = Property.newBuilder().setMultiValued(true);
        for (T value : values) property.addValues(convert.apply(Objects.requireNonNull(value, "List values cannot be null")));
        return property.build();
    }

    private static PropertyValue string(String value) { return PropertyValue.newBuilder().setStringValue(value).build(); }
    private static PropertyValue integral(long value) { return PropertyValue.newBuilder().setIntegerValue(value).build(); }
    private static PropertyValue floating(double value) {
        if (!Double.isFinite(value)) throw new IllegalArgumentException("Property numbers must be finite");
        return PropertyValue.newBuilder().setDoubleValue(value).build();
    }
    private static PropertyValue booleanValue(boolean value) { return PropertyValue.newBuilder().setBooleanValue(value).build(); }
    private static PropertyValue instant(Instant value) { return PropertyValue.newBuilder().setTimestampMillis(value.toEpochMilli()).build(); }
    private static PropertyValue reference(String value) { return PropertyValue.newBuilder().setNodeRefValue(value).build(); }
    private static PropertyValue qualifiedName(String value) { return PropertyValue.newBuilder().setQnameValue(value).build(); }
}

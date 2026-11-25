package org.saidone.protoalf.client;

import org.junit.jupiter.api.Test;
import org.saidone.protoalf.api.Property;
import org.saidone.protoalf.api.PropertyValue;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

class PropertiesTest {

    @Test
    void wireRoundTripPreservesScalarSingletonEmptyListAndExplicitNull() throws Exception {
        var values = Map.of(
                "scalar", Properties.text("a"),
                "singleton", Properties.texts("a"),
                "empty", Properties.texts(),
                "null", Properties.nullValue());
        for (var entry : values.entrySet())
            assertThat(Property.parseFrom(entry.getValue().toByteArray())).isEqualTo(entry.getValue());
        assertThat(values.get("scalar").getMultiValued()).isFalse();
        assertThat(values.get("singleton").getMultiValued()).isTrue();
        assertThat(values.get("empty").getValuesList()).isEmpty();
        assertThat(values.get("null").getValues(0).getValueCase()).isEqualTo(PropertyValue.ValueCase.NULL_VALUE);
    }

    @Test
    void factoriesPreserveKindsAndDefaultScalarValues() {
        var instant = Instant.parse("2026-11-02T10:00:00Z");
        assertThat(Properties.integer(0).getValues(0).getValueCase()).isEqualTo(PropertyValue.ValueCase.INTEGER_VALUE);
        assertThat(Properties.bool(false).getValues(0).getValueCase()).isEqualTo(PropertyValue.ValueCase.BOOLEAN_VALUE);
        assertThat(Properties.decimal(1.5).getValues(0).getDoubleValue()).isEqualTo(1.5);
        assertThat(Properties.timestamp(instant).getValues(0).getTimestampMillis()).isEqualTo(instant.toEpochMilli());
        assertThat(Properties.nodeRef("workspace://SpacesStore/doc").getValues(0).getNodeRefValue()).isEqualTo("workspace://SpacesStore/doc");
        assertThat(Properties.qname("cm:content").getValues(0).getQnameValue()).isEqualTo("cm:content");
        for (var property : List.of(Properties.integers(), Properties.decimals(), Properties.booleans(),
                Properties.timestamps(), Properties.nodeRefs(), Properties.qnames())) {
            assertThat(property.getMultiValued()).isTrue();
            assertThat(property.getValuesList()).isEmpty();
        }
        assertThat(Properties.integers(2, 1).getValuesList()).extracting(PropertyValue::getIntegerValue).containsExactly(2L, 1L);
    }

    @Test
    void invalidFactoryInputsAreRejected() {
        assertThatIllegalArgumentException().isThrownBy(() -> Properties.decimal(Double.NaN));
        assertThatIllegalArgumentException().isThrownBy(() -> Properties.decimals(Double.POSITIVE_INFINITY));
        assertThatNullPointerException().isThrownBy(() -> Properties.texts("a", null));
    }
}

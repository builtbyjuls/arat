package com.builtbyjuls.arat.platform.idempotency;

import static org.assertj.core.api.Assertions.assertThat;

import tools.jackson.databind.json.JsonMapper;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CanonicalCommandFieldsTest {

    @Test
    void canonicalizesMapKeysBeforeFingerprinting() {
        var first = Map.of("title", "Friday badminton", "headcount", Map.of("maximum", 10, "minimum", 4));
        var second = Map.of("headcount", Map.of("minimum", 4, "maximum", 10), "title", "Friday badminton");

        assertThat(CanonicalCommandFields.toJson(first)).isEqualTo(CanonicalCommandFields.toJson(second));
        assertThat(RequestFingerprint.fromCanonicalFields(first))
                .isEqualTo(RequestFingerprint.fromCanonicalFields(second));
    }

    @Test
    void canonicalizesObjectNodeKeysBeforeFingerprinting() {
        var objectMapper = JsonMapper.builder().build();
        var first = objectMapper.createObjectNode().put("operation", "groups.create");
        first.putObject("request")
                .put("title", "Friday badminton")
                .put("headcount", 4);
        var second = objectMapper.createObjectNode();
        second.putObject("request")
                .put("headcount", 4)
                .put("title", "Friday badminton");
        second.put("operation", "groups.create");

        assertThat(CanonicalCommandFields.toJson(first)).isEqualTo(CanonicalCommandFields.toJson(second));
        assertThat(RequestFingerprint.fromCanonicalFields(first))
                .isEqualTo(RequestFingerprint.fromCanonicalFields(second));
    }
}

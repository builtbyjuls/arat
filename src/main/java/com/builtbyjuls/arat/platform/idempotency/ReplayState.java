package com.builtbyjuls.arat.platform.idempotency;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.core.JacksonException;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

public final class ReplayState {

    private static final int MAX_SERIALIZED_BYTES = 16384;
    private final JsonNode value;

    private ReplayState(JsonNode value) {
        this.value = value.deepCopy();
    }

    public static ReplayState from(JsonNode value, ObjectMapper objectMapper) {
        Objects.requireNonNull(value, "value must not be null");
        Objects.requireNonNull(objectMapper, "objectMapper must not be null");
        rejectSensitiveFields(value);
        try {
            if (objectMapper.writeValueAsBytes(value).length > MAX_SERIALIZED_BYTES) {
                throw new IllegalArgumentException("replay state must not exceed 16384 bytes");
            }
        } catch (JacksonException exception) {
            throw new IllegalArgumentException("replay state cannot be serialized", exception);
        }
        return new ReplayState(value);
    }

    public JsonNode value() {
        return value.deepCopy();
    }

    private static void rejectSensitiveFields(JsonNode value) {
        if (value.isObject()) {
            var fields = value.properties().iterator();
            while (fields.hasNext()) {
                var field = fields.next();
                if (isSensitiveField(field.getKey())) {
                    throw new IllegalArgumentException("replay state must not contain sensitive fields");
                }
                rejectSensitiveFields(field.getValue());
            }
        } else if (value.isArray()) {
            value.forEach(ReplayState::rejectSensitiveFields);
        }
    }

    private static boolean isSensitiveField(String fieldName) {
        var normalized = fieldName.toLowerCase(Locale.ROOT);
        return normalized.contains("token")
                || normalized.contains("secret")
                || normalized.contains("password")
                || normalized.contains("authorization")
                || normalized.contains("cookie");
    }
}

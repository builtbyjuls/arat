package com.builtbyjuls.arat.platform.idempotency;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

public final class CanonicalCommandFields {

    private static final ObjectMapper CANONICAL_OBJECT_MAPPER = JsonMapper.builder()
            .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .build();

    private CanonicalCommandFields() {
    }

    public static String toJson(Object validatedFields) {
        Objects.requireNonNull(validatedFields, "validatedFields must not be null");
        try {
            return CANONICAL_OBJECT_MAPPER.writeValueAsString(canonicalize(CANONICAL_OBJECT_MAPPER.valueToTree(validatedFields)));
        } catch (JacksonException exception) {
            throw new IllegalArgumentException("validated command fields cannot be canonicalized", exception);
        }
    }

    private static tools.jackson.databind.JsonNode canonicalize(tools.jackson.databind.JsonNode value) {
        if (value.isObject()) {
            var fields = new TreeMap<String, tools.jackson.databind.JsonNode>();
            value.properties().forEach(field -> fields.put(field.getKey(), canonicalize(field.getValue())));
            var canonicalObject = CANONICAL_OBJECT_MAPPER.createObjectNode();
            fields.forEach(canonicalObject::set);
            return canonicalObject;
        }
        if (value.isArray()) {
            var canonicalArray = CANONICAL_OBJECT_MAPPER.createArrayNode();
            value.forEach(element -> canonicalArray.add(canonicalize(element)));
            return canonicalArray;
        }
        return value.deepCopy();
    }
}

package com.builtbyjuls.arat.platform.idempotency;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

public final class StoredReplayHeaders {

    private static final int MAX_SERIALIZED_BYTES = 2048;
    private final Map<String, String> values;

    private StoredReplayHeaders(Map<String, String> values) {
        this.values = Map.copyOf(values);
    }

    public static StoredReplayHeaders from(Map<String, String> responseHeaders) {
        Objects.requireNonNull(responseHeaders, "responseHeaders must not be null");
        var normalized = new LinkedHashMap<String, String>();
        responseHeaders.forEach((name, value) -> {
            var storedName = storedName(name);
            Objects.requireNonNull(value, "response header values must not be null");
            if (normalized.put(storedName, value) != null) {
                throw new IllegalArgumentException("response headers must not repeat " + storedName);
            }
        });
        var headers = new StoredReplayHeaders(normalized);
        if (headers.toJson().getBytes(StandardCharsets.UTF_8).length > MAX_SERIALIZED_BYTES) {
            throw new IllegalArgumentException("response headers must not exceed 2048 bytes");
        }
        return headers;
    }

    public Map<String, String> values() {
        return values;
    }

    String toJson() {
        return CanonicalCommandFields.toJson(values);
    }

    private static String storedName(String name) {
        Objects.requireNonNull(name, "response header names must not be null");
        return switch (name.toLowerCase(Locale.ROOT)) {
            case "etag" -> "ETag";
            case "location" -> "Location";
            default -> throw new IllegalArgumentException("only ETag and Location response headers may be stored");
        };
    }
}

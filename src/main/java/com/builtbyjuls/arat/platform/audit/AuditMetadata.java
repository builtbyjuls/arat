package com.builtbyjuls.arat.platform.audit;

import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

public final class AuditMetadata {

    private static final int MAX_REFERENCES = 18;
    private static final Set<String> FORBIDDEN_KEY_PARTS = Set.of(
            "token", "idempotency", "note", "sql", "stack", "request", "body", "secret", "password", "cookie");
    private final Map<String, UUID> references;

    private AuditMetadata(Map<String, UUID> references) {
        this.references = Map.copyOf(references);
    }

    public static AuditMetadata empty() {
        return new AuditMetadata(Map.of());
    }

    public static AuditMetadata references(Map<String, UUID> references) {
        if (references == null || references.size() > MAX_REFERENCES) {
            throw new IllegalArgumentException("audit metadata must contain at most 18 references");
        }
        var sanitized = new TreeMap<String, UUID>();
        references.forEach((name, value) -> {
            validateName(name);
            if (value == null) {
                throw new IllegalArgumentException("audit metadata reference values must not be null");
            }
            sanitized.put(name, value);
        });
        return new AuditMetadata(sanitized);
    }

    public Map<String, UUID> references() {
        return references;
    }

    private static void validateName(String name) {
        if (name == null || !name.matches("[a-z][A-Za-z0-9]{0,63}")) {
            throw new IllegalArgumentException("audit metadata reference names must be lower camel case");
        }
        var normalized = name.toLowerCase(Locale.ROOT);
        if (FORBIDDEN_KEY_PARTS.stream().anyMatch(normalized::contains)) {
            throw new IllegalArgumentException("audit metadata must not contain sensitive fields");
        }
    }
}

package com.builtbyjuls.arat.messaging.api;

import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;

/**
 * Versioned integration-event content with a JSON object payload.
 */
public record OutboxEventEnvelope(
        String eventType,
        long schemaVersion,
        OffsetDateTime occurredAt,
        String aggregateType,
        UUID aggregateId,
        long aggregateVersion,
        String traceId,
        String payload) {

    public OutboxEventEnvelope {
        eventType = requiredText(eventType, "eventType", 120);
        if (schemaVersion <= 0) {
            throw new IllegalArgumentException("schemaVersion must be positive");
        }
        Objects.requireNonNull(occurredAt, "occurredAt must not be null");
        aggregateType = requiredText(aggregateType, "aggregateType", 120);
        Objects.requireNonNull(aggregateId, "aggregateId must not be null");
        if (aggregateVersion <= 0) {
            throw new IllegalArgumentException("aggregateVersion must be positive");
        }
        traceId = requiredText(traceId, "traceId", 120);
        Objects.requireNonNull(payload, "payload must not be null");
    }

    private static String requiredText(String value, String name, int maximumLength) {
        if (value == null || value.isBlank() || value.length() > maximumLength) {
            throw new IllegalArgumentException(name + " must be non-blank and at most " + maximumLength + " characters");
        }
        return value;
    }
}

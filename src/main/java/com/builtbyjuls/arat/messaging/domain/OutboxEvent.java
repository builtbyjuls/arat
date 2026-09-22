package com.builtbyjuls.arat.messaging.domain;

import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;

public record OutboxEvent(
        UUID eventId,
        String businessKey,
        String eventType,
        long schemaVersion,
        OffsetDateTime occurredAt,
        String aggregateType,
        UUID aggregateId,
        long aggregateVersion,
        String traceId,
        String payload) {

    public OutboxEvent {
        Objects.requireNonNull(eventId, "eventId must not be null");
        businessKey = requiredText(businessKey, "businessKey", 256);
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

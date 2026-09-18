package com.builtbyjuls.arat.platform.audit;

import java.util.Objects;
import java.util.UUID;

public record AuditEvent(
        UUID eventId,
        UUID actorId,
        String action,
        String subjectType,
        UUID subjectId,
        UUID groupId,
        UUID planId,
        String correlationId,
        AuditMetadata metadata) {

    public AuditEvent {
        Objects.requireNonNull(eventId, "eventId must not be null");
        Objects.requireNonNull(actorId, "actorId must not be null");
        action = requiredText(action, "action", 120);
        subjectType = requiredText(subjectType, "subjectType", 120);
        Objects.requireNonNull(subjectId, "subjectId must not be null");
        correlationId = requiredText(correlationId, "correlationId", 120);
        Objects.requireNonNull(metadata, "metadata must not be null");
    }

    private static String requiredText(String value, String name, int maximumLength) {
        if (value == null || value.isBlank() || value.length() > maximumLength) {
            throw new IllegalArgumentException(name + " must be non-blank and at most " + maximumLength + " characters");
        }
        return value;
    }
}

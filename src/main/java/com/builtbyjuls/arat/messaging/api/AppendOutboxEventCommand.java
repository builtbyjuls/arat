package com.builtbyjuls.arat.messaging.api;

import java.util.Objects;
import java.util.UUID;

/**
 * Immutable notification intent to append to the caller's transaction.
 */
public record AppendOutboxEventCommand(
        UUID eventId,
        String businessKey,
        OutboxEventEnvelope envelope) {

    public AppendOutboxEventCommand {
        eventId = eventId == null ? UUID.randomUUID() : eventId;
        businessKey = requiredText(businessKey, "businessKey", 256);
        Objects.requireNonNull(envelope, "envelope must not be null");
    }

    private static String requiredText(String value, String name, int maximumLength) {
        if (value == null || value.isBlank() || value.length() > maximumLength) {
            throw new IllegalArgumentException(name + " must be non-blank and at most " + maximumLength + " characters");
        }
        return value;
    }
}

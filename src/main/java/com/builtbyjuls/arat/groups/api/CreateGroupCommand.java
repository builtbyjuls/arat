package com.builtbyjuls.arat.groups.api;

import java.util.Objects;
import java.util.UUID;

public record CreateGroupCommand(
        UUID actorId,
        String idempotencyKey,
        String name,
        String description,
        String correlationId) {

    public CreateGroupCommand {
        Objects.requireNonNull(actorId, "actorId must not be null");
        idempotencyKey = requiredText(idempotencyKey, "idempotencyKey", 255);
        name = requiredText(name, "name", 80);
        description = Objects.requireNonNullElse(description, "");
        if (description.length() > 500) {
            throw new IllegalArgumentException("description must be at most 500 characters");
        }
        correlationId = requiredText(correlationId, "correlationId", 120);
    }

    private static String requiredText(String value, String name, int maximumLength) {
        if (value == null || value.isBlank() || value.length() > maximumLength) {
            throw new IllegalArgumentException(name + " must be non-blank and at most " + maximumLength + " characters");
        }
        return value;
    }
}

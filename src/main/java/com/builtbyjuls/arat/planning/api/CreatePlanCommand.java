package com.builtbyjuls.arat.planning.api;

import java.util.Objects;
import java.util.UUID;

public record CreatePlanCommand(
        UUID actorId,
        UUID groupId,
        String idempotencyKey,
        CreatePlanRequest request,
        String correlationId) {

    public CreatePlanCommand {
        Objects.requireNonNull(actorId, "actorId must not be null");
        Objects.requireNonNull(groupId, "groupId must not be null");
        idempotencyKey = requiredText(idempotencyKey, "idempotencyKey", 255);
        Objects.requireNonNull(request, "request must not be null");
        correlationId = requiredText(correlationId, "correlationId", 120);
    }

    private static String requiredText(String value, String name, int maximumLength) {
        if (value == null || value.isBlank() || value.length() > maximumLength) {
            throw new IllegalArgumentException(name + " must be non-blank and at most " + maximumLength + " characters");
        }
        return value;
    }
}

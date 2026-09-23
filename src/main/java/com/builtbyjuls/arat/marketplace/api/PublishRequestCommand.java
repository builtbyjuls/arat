package com.builtbyjuls.arat.marketplace.api;

import java.util.Objects;
import java.util.UUID;

public record PublishRequestCommand(
        UUID actorId,
        UUID planId,
        UUID finalizationId,
        long expectedPlanVersion,
        String idempotencyKey,
        String correlationId) {

    public PublishRequestCommand {
        Objects.requireNonNull(actorId, "actorId must not be null");
        Objects.requireNonNull(planId, "planId must not be null");
        Objects.requireNonNull(finalizationId, "finalizationId must not be null");
        if (expectedPlanVersion < 1) {
            throw new IllegalArgumentException("expectedPlanVersion must be positive");
        }
        idempotencyKey = requiredText(idempotencyKey, "idempotencyKey", 255);
        correlationId = requiredText(correlationId, "correlationId", 120);
    }

    private static String requiredText(String value, String name, int maximumLength) {
        if (value == null || value.isBlank() || value.length() > maximumLength) {
            throw new IllegalArgumentException(name + " must be non-blank and at most " + maximumLength + " characters");
        }
        return value;
    }
}

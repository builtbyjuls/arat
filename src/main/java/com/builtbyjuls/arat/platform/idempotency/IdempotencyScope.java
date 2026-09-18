package com.builtbyjuls.arat.platform.idempotency;

import java.util.Objects;
import java.util.UUID;

public record IdempotencyScope(UUID actorId, String operation, String idempotencyKey) {

    private static final int MAX_OPERATION_LENGTH = 120;
    private static final int MAX_KEY_LENGTH = 255;

    public IdempotencyScope {
        Objects.requireNonNull(actorId, "actorId must not be null");
        requireText(operation, "operation", MAX_OPERATION_LENGTH);
        requireText(idempotencyKey, "idempotencyKey", MAX_KEY_LENGTH);
    }

    private static void requireText(String value, String name, int maximumLength) {
        Objects.requireNonNull(value, name + " must not be null");
        if (value.isBlank() || value.length() > maximumLength) {
            throw new IllegalArgumentException(name + " must be non-blank and at most " + maximumLength + " characters");
        }
    }
}

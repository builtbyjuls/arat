package com.builtbyjuls.arat.planning.api;

import java.util.Objects;
import java.util.UUID;

public record CloseRequestVersionCommand(
        UUID planId,
        UUID requestId,
        long expectedPlanVersion) {

    public CloseRequestVersionCommand {
        Objects.requireNonNull(planId, "planId must not be null");
        Objects.requireNonNull(requestId, "requestId must not be null");
        if (expectedPlanVersion < 1) {
            throw new IllegalArgumentException("expectedPlanVersion must be positive");
        }
    }
}

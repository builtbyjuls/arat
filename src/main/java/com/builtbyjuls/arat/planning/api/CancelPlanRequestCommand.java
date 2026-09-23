package com.builtbyjuls.arat.planning.api;

import java.util.Objects;
import java.util.UUID;

public record CancelPlanRequestCommand(UUID planId, long expectedPlanVersion) {

    public CancelPlanRequestCommand {
        Objects.requireNonNull(planId, "planId must not be null");
        if (expectedPlanVersion < 1) {
            throw new IllegalArgumentException("expectedPlanVersion must be positive");
        }
    }
}

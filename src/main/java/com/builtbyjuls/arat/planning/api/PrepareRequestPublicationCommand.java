package com.builtbyjuls.arat.planning.api;

import java.util.Objects;
import java.util.UUID;

public record PrepareRequestPublicationCommand(
        UUID planId,
        UUID finalizationId,
        long expectedPlanVersion) {

    public PrepareRequestPublicationCommand {
        Objects.requireNonNull(planId, "planId must not be null");
        Objects.requireNonNull(finalizationId, "finalizationId must not be null");
        if (expectedPlanVersion < 1) {
            throw new IllegalArgumentException("expectedPlanVersion must be positive");
        }
    }
}

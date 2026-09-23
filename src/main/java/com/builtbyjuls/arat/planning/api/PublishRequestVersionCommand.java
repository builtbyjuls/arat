package com.builtbyjuls.arat.planning.api;

import java.util.Objects;
import java.util.UUID;

public record PublishRequestVersionCommand(
        UUID requestId,
        UUID planId,
        UUID finalizationId,
        UUID publishedByAccountId,
        long expectedPlanVersion) {

    public PublishRequestVersionCommand {
        Objects.requireNonNull(requestId, "requestId must not be null");
        Objects.requireNonNull(planId, "planId must not be null");
        Objects.requireNonNull(finalizationId, "finalizationId must not be null");
        Objects.requireNonNull(publishedByAccountId, "publishedByAccountId must not be null");
        if (expectedPlanVersion < 1) {
            throw new IllegalArgumentException("expectedPlanVersion must be positive");
        }
    }
}

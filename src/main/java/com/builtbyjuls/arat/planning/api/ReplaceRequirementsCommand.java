package com.builtbyjuls.arat.planning.api;

import java.util.Objects;
import java.util.UUID;

public record ReplaceRequirementsCommand(
        UUID actorId,
        UUID planId,
        long expectedPlanVersion,
        RequirementReplacementRequest request,
        String correlationId) {

    public ReplaceRequirementsCommand {
        Objects.requireNonNull(actorId, "actorId must not be null");
        Objects.requireNonNull(planId, "planId must not be null");
        if (expectedPlanVersion < 1) {
            throw new IllegalArgumentException("expectedPlanVersion must be positive");
        }
        Objects.requireNonNull(request, "request must not be null");
        if (correlationId == null || correlationId.isBlank() || correlationId.length() > 120) {
            throw new IllegalArgumentException("correlationId must be non-blank and at most 120 characters");
        }
    }
}

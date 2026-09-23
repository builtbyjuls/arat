package com.builtbyjuls.arat.planning.api;

import java.util.UUID;

public record FinalizeRequirementsCommand(
        UUID actorId,
        UUID planId,
        long expectedPlanVersion,
        FinalizeRequirementsRequest request,
        String idempotencyKey,
        String correlationId) {
}

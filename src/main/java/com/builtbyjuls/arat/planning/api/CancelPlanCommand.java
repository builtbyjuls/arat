package com.builtbyjuls.arat.planning.api;

import java.util.UUID;

public record CancelPlanCommand(
        UUID actorId,
        UUID planId,
        long expectedPlanVersion,
        String idempotencyKey,
        String correlationId) {
}

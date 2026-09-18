package com.builtbyjuls.arat.groups.api;

import java.util.UUID;

public record TransferOrganizerCommand(
        UUID actorId,
        UUID groupId,
        UUID targetAccountId,
        long expectedGroupVersion,
        String idempotencyKey,
        String correlationId) {
}

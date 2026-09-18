package com.builtbyjuls.arat.groups.api;

import java.util.UUID;

public record LeaveGroupCommand(
        UUID actorId,
        UUID groupId,
        String idempotencyKey,
        String correlationId) {
}

package com.builtbyjuls.arat.groups.api;

import java.util.UUID;

public record RemoveGroupMemberCommand(
        UUID actorId,
        UUID groupId,
        UUID targetAccountId,
        String idempotencyKey,
        String correlationId) {
}

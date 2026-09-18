package com.builtbyjuls.arat.groups.api;

import java.util.UUID;

public record RevokeInvitationCommand(
        UUID actorId,
        UUID groupId,
        UUID inviteId,
        String idempotencyKey,
        String correlationId) {
}

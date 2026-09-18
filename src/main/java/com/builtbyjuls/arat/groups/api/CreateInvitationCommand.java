package com.builtbyjuls.arat.groups.api;

import java.util.UUID;

public record CreateInvitationCommand(
        UUID actorId,
        UUID groupId,
        UUID inviteeAccountId,
        int expiryHours,
        String idempotencyKey,
        String correlationId) {
}

package com.builtbyjuls.arat.groups.api;

import java.util.UUID;

public record AcceptInvitationCommand(
        UUID actorId,
        String token,
        String idempotencyKey,
        String correlationId) {
}

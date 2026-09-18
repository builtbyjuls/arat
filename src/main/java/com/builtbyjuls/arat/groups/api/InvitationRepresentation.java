package com.builtbyjuls.arat.groups.api;

import java.time.OffsetDateTime;
import java.util.UUID;

public record InvitationRepresentation(
        UUID inviteId,
        UUID groupId,
        UUID inviteeAccountId,
        OffsetDateTime expiresAt,
        String token) {
}

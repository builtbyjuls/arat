package com.builtbyjuls.arat.groups.domain;

import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;

public record Invitation(
        UUID inviteId,
        UUID groupId,
        UUID inviteeAccountId,
        String tokenKeyId,
        byte[] nonce,
        String tokenDigest,
        InvitationState state,
        OffsetDateTime expiresAt,
        UUID createdByAccountId,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {

    public Invitation {
        Objects.requireNonNull(inviteId, "inviteId must not be null");
        Objects.requireNonNull(groupId, "groupId must not be null");
        Objects.requireNonNull(inviteeAccountId, "inviteeAccountId must not be null");
        if (tokenKeyId == null || tokenKeyId.isBlank() || tokenKeyId.length() > 80) {
            throw new IllegalArgumentException("tokenKeyId must be between 1 and 80 characters");
        }
        if (nonce == null || nonce.length != 32) {
            throw new IllegalArgumentException("nonce must contain exactly 32 bytes");
        }
        nonce = Arrays.copyOf(nonce, nonce.length);
        if (tokenDigest == null || !tokenDigest.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("tokenDigest must be a SHA-256 hex digest");
        }
        Objects.requireNonNull(state, "state must not be null");
        Objects.requireNonNull(expiresAt, "expiresAt must not be null");
        Objects.requireNonNull(createdByAccountId, "createdByAccountId must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        Objects.requireNonNull(updatedAt, "updatedAt must not be null");
    }

    @Override
    public byte[] nonce() {
        return Arrays.copyOf(nonce, nonce.length);
    }
}

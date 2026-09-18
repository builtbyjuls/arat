package com.builtbyjuls.arat.groups.domain;

import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;

public record Membership(
        UUID groupId,
        UUID accountId,
        MembershipRole role,
        MembershipStatus status,
        OffsetDateTime joinedAt,
        OffsetDateTime endedAt) {

    public Membership {
        Objects.requireNonNull(groupId, "groupId must not be null");
        Objects.requireNonNull(accountId, "accountId must not be null");
        Objects.requireNonNull(role, "role must not be null");
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(joinedAt, "joinedAt must not be null");
        if (status == MembershipStatus.ACTIVE && endedAt != null) {
            throw new IllegalArgumentException("active membership must not have endedAt");
        }
        if (status != MembershipStatus.ACTIVE && endedAt == null) {
            throw new IllegalArgumentException("inactive membership must have endedAt");
        }
    }
}

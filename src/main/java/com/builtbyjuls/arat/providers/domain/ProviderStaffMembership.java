package com.builtbyjuls.arat.providers.domain;

import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;

public record ProviderStaffMembership(
        UUID providerId,
        UUID accountId,
        ProviderStaffRole role,
        ProviderStaffStatus status,
        OffsetDateTime joinedAt,
        OffsetDateTime removedAt) {

    public ProviderStaffMembership {
        Objects.requireNonNull(providerId, "providerId must not be null");
        Objects.requireNonNull(accountId, "accountId must not be null");
        Objects.requireNonNull(role, "role must not be null");
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(joinedAt, "joinedAt must not be null");
        if ((status == ProviderStaffStatus.ACTIVE) != (removedAt == null)) {
            throw new IllegalArgumentException("removedAt must match staff membership status");
        }
    }
}

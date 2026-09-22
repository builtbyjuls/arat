package com.builtbyjuls.arat.providers.domain;

import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;

public record ProviderOrganization(
        UUID providerId,
        String displayName,
        ProviderOrganizationStatus status,
        ProviderVerificationStatus verificationStatus,
        long version,
        long eligibilityVersion,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {

    public ProviderOrganization {
        Objects.requireNonNull(providerId, "providerId must not be null");
        if (displayName == null || displayName.isBlank() || displayName.length() > 120) {
            throw new IllegalArgumentException("displayName must be between 1 and 120 characters");
        }
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(verificationStatus, "verificationStatus must not be null");
        if (version < 1) {
            throw new IllegalArgumentException("version must be positive");
        }
        if (eligibilityVersion < 1) {
            throw new IllegalArgumentException("eligibilityVersion must be positive");
        }
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        Objects.requireNonNull(updatedAt, "updatedAt must not be null");
    }
}

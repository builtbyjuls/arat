package com.builtbyjuls.arat.providers.domain;

import java.util.Objects;
import java.util.UUID;

public record ProviderSuspension(
        UUID suspensionId,
        UUID providerId,
        UUID suspendedByAccountId,
        String reason) {

    public ProviderSuspension {
        Objects.requireNonNull(suspensionId, "suspensionId must not be null");
        Objects.requireNonNull(providerId, "providerId must not be null");
        Objects.requireNonNull(suspendedByAccountId, "suspendedByAccountId must not be null");
        if (reason == null || reason.isBlank() || reason.length() > 500 || !reason.equals(reason.strip())) {
            throw new IllegalArgumentException("reason must be trimmed and contain from 1 to 500 characters");
        }
    }
}

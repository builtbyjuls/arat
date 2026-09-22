package com.builtbyjuls.arat.providers.api;

import com.builtbyjuls.arat.identity.api.PlatformRole;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public record SuspendProviderCommand(
        UUID actorId,
        Set<PlatformRole> platformRoles,
        UUID providerId,
        String idempotencyKey,
        String reason,
        String correlationId) {

    public SuspendProviderCommand {
        Objects.requireNonNull(actorId, "actorId must not be null");
        platformRoles = Set.copyOf(Objects.requireNonNull(platformRoles, "platformRoles must not be null"));
        Objects.requireNonNull(providerId, "providerId must not be null");
        idempotencyKey = requiredText(idempotencyKey, "idempotencyKey", 255);
        reason = requiredTrimmedText(reason, "reason", 500);
        correlationId = requiredText(correlationId, "correlationId", 120);
    }

    private static String requiredText(String value, String name, int maximumLength) {
        if (value == null || value.isBlank() || value.length() > maximumLength) {
            throw new IllegalArgumentException(name + " must be non-blank and at most " + maximumLength + " characters");
        }
        return value;
    }

    private static String requiredTrimmedText(String value, String name, int maximumLength) {
        if (value == null) {
            throw new IllegalArgumentException(name + " must not be null");
        }
        var trimmed = value.strip();
        if (trimmed.isEmpty() || trimmed.length() > maximumLength) {
            throw new IllegalArgumentException(name + " must contain from 1 to " + maximumLength + " characters");
        }
        return trimmed;
    }
}

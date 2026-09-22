package com.builtbyjuls.arat.providers.api;

import com.builtbyjuls.arat.identity.api.PlatformRole;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public record RestoreProviderCommand(
        UUID actorId,
        Set<PlatformRole> platformRoles,
        UUID providerId,
        String idempotencyKey,
        String correlationId) {

    public RestoreProviderCommand {
        Objects.requireNonNull(actorId, "actorId must not be null");
        platformRoles = Set.copyOf(Objects.requireNonNull(platformRoles, "platformRoles must not be null"));
        Objects.requireNonNull(providerId, "providerId must not be null");
        idempotencyKey = requiredText(idempotencyKey, "idempotencyKey", 255);
        correlationId = requiredText(correlationId, "correlationId", 120);
    }

    private static String requiredText(String value, String name, int maximumLength) {
        if (value == null || value.isBlank() || value.length() > maximumLength) {
            throw new IllegalArgumentException(name + " must be non-blank and at most " + maximumLength + " characters");
        }
        return value;
    }
}

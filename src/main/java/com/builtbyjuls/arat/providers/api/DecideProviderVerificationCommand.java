package com.builtbyjuls.arat.providers.api;

import com.builtbyjuls.arat.identity.api.PlatformRole;
import com.builtbyjuls.arat.providers.domain.ProviderVerificationDecision;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public record DecideProviderVerificationCommand(
        UUID actorId,
        Set<PlatformRole> platformRoles,
        UUID providerId,
        UUID submissionId,
        String idempotencyKey,
        ProviderVerificationDecision decision,
        String note,
        String correlationId) {

    public DecideProviderVerificationCommand {
        Objects.requireNonNull(actorId, "actorId must not be null");
        platformRoles = Set.copyOf(Objects.requireNonNull(platformRoles, "platformRoles must not be null"));
        Objects.requireNonNull(providerId, "providerId must not be null");
        Objects.requireNonNull(submissionId, "submissionId must not be null");
        idempotencyKey = requiredText(idempotencyKey, "idempotencyKey", 255);
        Objects.requireNonNull(decision, "decision must not be null");
        note = optionalTrimmedText(note, "note", 500);
        correlationId = requiredText(correlationId, "correlationId", 120);
    }

    private static String requiredText(String value, String name, int maximumLength) {
        if (value == null || value.isBlank() || value.length() > maximumLength) {
            throw new IllegalArgumentException(name + " must be non-blank and at most " + maximumLength + " characters");
        }
        return value;
    }

    private static String optionalTrimmedText(String value, String name, int maximumLength) {
        if (value == null) {
            return null;
        }
        var trimmed = value.strip();
        if (trimmed.isEmpty() || trimmed.length() > maximumLength) {
            throw new IllegalArgumentException(name + " must contain from 1 to " + maximumLength + " characters");
        }
        return trimmed;
    }
}

package com.builtbyjuls.arat.providers.api;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record SubmitProviderVerificationCommand(
        UUID actorId,
        UUID providerId,
        String idempotencyKey,
        List<String> evidenceReferences,
        String correlationId) {

    public SubmitProviderVerificationCommand {
        Objects.requireNonNull(actorId, "actorId must not be null");
        Objects.requireNonNull(providerId, "providerId must not be null");
        idempotencyKey = requiredText(idempotencyKey, "idempotencyKey", 255);
        evidenceReferences = canonicalEvidenceReferences(evidenceReferences);
        correlationId = requiredText(correlationId, "correlationId", 120);
    }

    private static List<String> canonicalEvidenceReferences(List<String> evidenceReferences) {
        if (evidenceReferences == null || evidenceReferences.size() < 1 || evidenceReferences.size() > 10) {
            throw new IllegalArgumentException("evidenceReferences must contain from 1 to 10 values");
        }
        var canonical = evidenceReferences.stream()
                .map(reference -> requiredText(reference, "evidenceReference", 256))
                .toList();
        if (canonical.stream().distinct().count() != canonical.size()) {
            throw new IllegalArgumentException("evidenceReferences must be unique");
        }
        return canonical;
    }

    private static String requiredText(String value, String name, int maximumLength) {
        if (value == null || value.length() > maximumLength || !value.matches("[ -~]+")) {
            throw new IllegalArgumentException(name + " must be printable ASCII and at most " + maximumLength + " characters");
        }
        return value;
    }
}

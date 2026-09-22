package com.builtbyjuls.arat.providers.domain;

import java.util.Objects;
import java.util.UUID;

public record ProviderVerificationDecisionRecord(
        UUID decisionId,
        UUID providerId,
        UUID submissionId,
        UUID decidedByAccountId,
        ProviderVerificationDecision decision,
        String note) {

    public ProviderVerificationDecisionRecord {
        Objects.requireNonNull(decisionId, "decisionId must not be null");
        Objects.requireNonNull(providerId, "providerId must not be null");
        Objects.requireNonNull(submissionId, "submissionId must not be null");
        Objects.requireNonNull(decidedByAccountId, "decidedByAccountId must not be null");
        Objects.requireNonNull(decision, "decision must not be null");
        if (note != null && (note.isBlank() || note.length() > 500 || !note.equals(note.strip()))) {
            throw new IllegalArgumentException("note must be trimmed and contain from 1 to 500 characters");
        }
    }
}

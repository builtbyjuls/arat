package com.builtbyjuls.arat.providers.domain;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record ProviderVerificationSubmission(
        UUID submissionId,
        UUID providerId,
        UUID submittedByAccountId,
        List<String> evidenceReferences) {

    public ProviderVerificationSubmission {
        Objects.requireNonNull(submissionId, "submissionId must not be null");
        Objects.requireNonNull(providerId, "providerId must not be null");
        Objects.requireNonNull(submittedByAccountId, "submittedByAccountId must not be null");
        evidenceReferences = List.copyOf(evidenceReferences);
        if (evidenceReferences.size() < 1 || evidenceReferences.size() > 10) {
            throw new IllegalArgumentException("evidenceReferences must contain from 1 to 10 values");
        }
    }
}

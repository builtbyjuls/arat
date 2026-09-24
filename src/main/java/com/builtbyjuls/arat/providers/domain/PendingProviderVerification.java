package com.builtbyjuls.arat.providers.domain;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record PendingProviderVerification(
        UUID submissionId,
        UUID providerId,
        long providerVersion,
        String displayName,
        ProviderVerificationStatus verificationStatus,
        List<ProviderCategory> supportedCategories,
        List<String> serviceAreaCodes,
        OffsetDateTime submittedAt,
        List<String> evidenceReferences) {

    public PendingProviderVerification {
        Objects.requireNonNull(submissionId, "submissionId must not be null");
        Objects.requireNonNull(providerId, "providerId must not be null");
        Objects.requireNonNull(displayName, "displayName must not be null");
        Objects.requireNonNull(verificationStatus, "verificationStatus must not be null");
        Objects.requireNonNull(submittedAt, "submittedAt must not be null");
        supportedCategories = List.copyOf(supportedCategories);
        serviceAreaCodes = List.copyOf(serviceAreaCodes);
        evidenceReferences = List.copyOf(evidenceReferences);
    }
}

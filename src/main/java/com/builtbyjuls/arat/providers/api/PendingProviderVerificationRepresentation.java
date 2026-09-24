package com.builtbyjuls.arat.providers.api;

import com.builtbyjuls.arat.providers.domain.PendingProviderVerification;
import com.builtbyjuls.arat.providers.domain.ProviderCategory;
import com.builtbyjuls.arat.providers.domain.ProviderVerificationStatus;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record PendingProviderVerificationRepresentation(
        UUID submissionId,
        UUID providerId,
        long providerVersion,
        String displayName,
        ProviderVerificationStatus verificationStatus,
        List<ProviderCategory> supportedCategories,
        List<String> serviceAreaCodes,
        OffsetDateTime submittedAt,
        List<String> evidenceReferences) {

    public PendingProviderVerificationRepresentation {
        supportedCategories = List.copyOf(supportedCategories);
        serviceAreaCodes = List.copyOf(serviceAreaCodes);
        evidenceReferences = List.copyOf(evidenceReferences);
    }

    public static PendingProviderVerificationRepresentation from(PendingProviderVerification verification) {
        return new PendingProviderVerificationRepresentation(
                verification.submissionId(),
                verification.providerId(),
                verification.providerVersion(),
                verification.displayName(),
                verification.verificationStatus(),
                verification.supportedCategories(),
                verification.serviceAreaCodes(),
                verification.submittedAt(),
                verification.evidenceReferences());
    }
}

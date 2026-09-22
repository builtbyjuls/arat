package com.builtbyjuls.arat.providers.api;

import com.builtbyjuls.arat.providers.domain.ProviderOrganization;
import java.util.UUID;

public record ProviderVerificationSubmissionRepresentation(
        UUID submissionId,
        UUID providerId,
        long evidenceCount,
        long providerVersion) {

    public static ProviderVerificationSubmissionRepresentation from(
            UUID submissionId, ProviderOrganization organization, int evidenceCount) {
        return new ProviderVerificationSubmissionRepresentation(
                submissionId, organization.providerId(), evidenceCount, organization.version());
    }
}

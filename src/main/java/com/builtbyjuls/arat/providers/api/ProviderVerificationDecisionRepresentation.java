package com.builtbyjuls.arat.providers.api;

import com.builtbyjuls.arat.providers.domain.ProviderOrganization;
import com.builtbyjuls.arat.providers.domain.ProviderVerificationDecision;
import com.builtbyjuls.arat.providers.domain.ProviderVerificationStatus;
import java.util.UUID;

public record ProviderVerificationDecisionRepresentation(
        UUID decisionId,
        UUID providerId,
        UUID submissionId,
        ProviderVerificationDecision decision,
        String note,
        ProviderVerificationStatus verificationStatus,
        long providerVersion,
        long eligibilityVersion) {

    public static ProviderVerificationDecisionRepresentation from(
            UUID decisionId,
            UUID submissionId,
            ProviderVerificationDecision decision,
            String note,
            ProviderOrganization organization) {
        return new ProviderVerificationDecisionRepresentation(
                decisionId,
                organization.providerId(),
                submissionId,
                decision,
                note,
                organization.verificationStatus(),
                organization.version(),
                organization.eligibilityVersion());
    }
}

package com.builtbyjuls.arat.providers.api;

import com.builtbyjuls.arat.providers.domain.ProviderOrganization;
import com.builtbyjuls.arat.providers.domain.ProviderVerificationStatus;
import java.util.UUID;

public record ProviderSuspensionRepresentation(
        UUID suspensionId,
        UUID providerId,
        String reason,
        ProviderVerificationStatus verificationStatus,
        long providerVersion,
        long eligibilityVersion) {

    public static ProviderSuspensionRepresentation from(
            UUID suspensionId,
            String reason,
            ProviderOrganization organization) {
        return new ProviderSuspensionRepresentation(
                suspensionId,
                organization.providerId(),
                reason,
                organization.verificationStatus(),
                organization.version(),
                organization.eligibilityVersion());
    }
}

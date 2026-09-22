package com.builtbyjuls.arat.providers.api;

import com.builtbyjuls.arat.providers.domain.ProviderOrganization;
import com.builtbyjuls.arat.providers.domain.ProviderVerificationStatus;
import java.util.UUID;

public record ProviderRestorationRepresentation(
        UUID providerId,
        ProviderVerificationStatus verificationStatus,
        long providerVersion,
        long eligibilityVersion) {

    public static ProviderRestorationRepresentation from(ProviderOrganization organization) {
        return new ProviderRestorationRepresentation(
                organization.providerId(),
                organization.verificationStatus(),
                organization.version(),
                organization.eligibilityVersion());
    }
}

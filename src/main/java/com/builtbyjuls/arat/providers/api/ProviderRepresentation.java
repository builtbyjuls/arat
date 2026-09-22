package com.builtbyjuls.arat.providers.api;

import com.builtbyjuls.arat.providers.domain.ProviderCategory;
import com.builtbyjuls.arat.providers.domain.ProviderOrganization;
import com.builtbyjuls.arat.providers.domain.ProviderVerificationStatus;
import java.util.List;
import java.util.UUID;

public record ProviderRepresentation(
        UUID providerId,
        String displayName,
        List<ProviderCategory> supportedCategories,
        List<String> serviceAreaCodes,
        ProviderVerificationStatus verificationStatus,
        long version) {

    public static ProviderRepresentation from(
            ProviderOrganization organization,
            List<ProviderCategory> supportedCategories,
            List<String> serviceAreaCodes) {
        return new ProviderRepresentation(
                organization.providerId(),
                organization.displayName(),
                List.copyOf(supportedCategories),
                List.copyOf(serviceAreaCodes),
                organization.verificationStatus(),
                organization.version());
    }
}

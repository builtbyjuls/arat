package com.builtbyjuls.arat.providers.api;

import com.builtbyjuls.arat.providers.domain.ProviderCategory;
import com.builtbyjuls.arat.providers.domain.ProviderOrganization;
import com.builtbyjuls.arat.providers.domain.ProviderStaffRole;
import com.builtbyjuls.arat.providers.domain.ProviderVerificationStatus;
import java.util.List;
import java.util.UUID;

public record ProviderDetailRepresentation(
        UUID providerId,
        String displayName,
        List<ProviderCategory> supportedCategories,
        List<String> serviceAreaCodes,
        ProviderVerificationStatus verificationStatus,
        long version,
        ProviderStaffRole callerStaffRole) {

    public static ProviderDetailRepresentation from(
            ProviderOrganization organization,
            List<ProviderCategory> supportedCategories,
            List<String> serviceAreaCodes,
            ProviderStaffRole callerStaffRole) {
        return new ProviderDetailRepresentation(
                organization.providerId(),
                organization.displayName(),
                List.copyOf(supportedCategories),
                List.copyOf(serviceAreaCodes),
                organization.verificationStatus(),
                organization.version(),
                callerStaffRole);
    }
}

package com.builtbyjuls.arat.providers.api;

import com.builtbyjuls.arat.providers.domain.ProviderCategory;
import com.builtbyjuls.arat.providers.domain.ProviderStaffRole;
import com.builtbyjuls.arat.providers.domain.ProviderVerificationStatus;
import com.builtbyjuls.arat.providers.domain.ProviderWorkspace;
import java.util.List;
import java.util.UUID;

public record ProviderSummaryRepresentation(
        UUID providerId,
        String displayName,
        ProviderVerificationStatus verificationStatus,
        long version,
        ProviderStaffRole callerStaffRole,
        List<ProviderCategory> supportedCategories,
        List<String> serviceAreaCodes) {

    public ProviderSummaryRepresentation {
        supportedCategories = List.copyOf(supportedCategories);
        serviceAreaCodes = List.copyOf(serviceAreaCodes);
    }

    public static ProviderSummaryRepresentation from(ProviderWorkspace workspace) {
        var provider = workspace.organization();
        return new ProviderSummaryRepresentation(
                provider.providerId(),
                provider.displayName(),
                provider.verificationStatus(),
                provider.version(),
                workspace.role(),
                workspace.supportedCategories(),
                workspace.serviceAreaCodes());
    }
}

package com.builtbyjuls.arat.providers.domain;

import java.util.List;

public record ProviderWorkspace(
        ProviderOrganization organization,
        ProviderStaffRole role,
        List<ProviderCategory> supportedCategories,
        List<String> serviceAreaCodes) {

    public ProviderWorkspace {
        supportedCategories = List.copyOf(supportedCategories);
        serviceAreaCodes = List.copyOf(serviceAreaCodes);
    }
}

package com.builtbyjuls.arat.providers.api;

/**
 * Provider-safe criteria used to find organizations eligible for a request.
 */
public record ProviderEligibilityCriteria(String category, String serviceAreaCode) {

    public ProviderEligibilityCriteria {
        if (category == null || category.isBlank() || category.length() > 20 || !category.equals(category.trim())) {
            throw new IllegalArgumentException("category must be trimmed, non-blank, and at most 20 characters");
        }
        if (serviceAreaCode == null
                || serviceAreaCode.isBlank()
                || serviceAreaCode.length() > 64
                || !serviceAreaCode.equals(serviceAreaCode.trim())) {
            throw new IllegalArgumentException("serviceAreaCode must be trimmed, non-blank, and at most 64 characters");
        }
    }
}

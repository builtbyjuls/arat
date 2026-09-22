package com.builtbyjuls.arat.matching.api;

/**
 * Provider-safe request criteria used to select a bounded recipient audience.
 */
public record MatchRequestRecipientsCommand(String category, String serviceAreaCode) {

    public MatchRequestRecipientsCommand {
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

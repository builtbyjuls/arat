package com.builtbyjuls.arat.providers.api;

import java.util.Objects;
import java.util.UUID;

/**
 * The minimum eligibility observation needed to create a recipient grant.
 */
public record ProviderEligibilityCandidate(UUID providerId, long eligibilityVersion) {

    public ProviderEligibilityCandidate {
        Objects.requireNonNull(providerId, "providerId must not be null");
        if (eligibilityVersion < 1) {
            throw new IllegalArgumentException("eligibilityVersion must be positive");
        }
    }
}

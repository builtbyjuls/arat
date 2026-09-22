package com.builtbyjuls.arat.matching.api;

import java.util.Objects;
import java.util.UUID;

/**
 * An eligible provider observation retained by a future recipient grant.
 */
public record RequestRecipientCandidate(UUID providerId, long eligibilityVersion) {

    public RequestRecipientCandidate {
        Objects.requireNonNull(providerId, "providerId must not be null");
        if (eligibilityVersion < 1) {
            throw new IllegalArgumentException("eligibilityVersion must be positive");
        }
    }
}

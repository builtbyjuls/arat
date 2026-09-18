package com.builtbyjuls.arat.identity.api;

import java.util.Objects;
import java.util.UUID;

public record Account(UUID accountId, String displayName) {

    public Account {
        Objects.requireNonNull(accountId, "accountId must not be null");
        Objects.requireNonNull(displayName, "displayName must not be null");
        if (displayName.isBlank()) {
            throw new IllegalArgumentException("displayName must not be blank");
        }
    }
}

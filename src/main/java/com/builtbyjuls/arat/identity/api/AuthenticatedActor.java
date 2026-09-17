package com.builtbyjuls.arat.identity.api;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public record AuthenticatedActor(UUID accountId, Set<PlatformRole> platformRoles) {

    public AuthenticatedActor {
        Objects.requireNonNull(accountId, "accountId must not be null");
        platformRoles = Set.copyOf(Objects.requireNonNull(platformRoles, "platformRoles must not be null"));
    }
}

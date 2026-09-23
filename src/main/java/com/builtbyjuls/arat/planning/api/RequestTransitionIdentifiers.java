package com.builtbyjuls.arat.planning.api;

import java.util.Objects;
import java.util.UUID;

public record RequestTransitionIdentifiers(UUID requestId, UUID planId, UUID groupId) {

    public RequestTransitionIdentifiers {
        Objects.requireNonNull(requestId, "requestId must not be null");
        Objects.requireNonNull(planId, "planId must not be null");
        Objects.requireNonNull(groupId, "groupId must not be null");
    }
}

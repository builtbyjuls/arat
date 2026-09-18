package com.builtbyjuls.arat.platform.idempotency;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public record CompletedIdempotencyResponse(
        int status,
        ReplayState replayState,
        StoredReplayHeaders headers,
        UUID resourceId) {

    public CompletedIdempotencyResponse {
        if (status < 100 || status > 599) {
            throw new IllegalArgumentException("status must be a valid HTTP status");
        }
        Objects.requireNonNull(replayState, "replayState must not be null");
        Objects.requireNonNull(headers, "headers must not be null");
    }

    public Optional<UUID> resourceIdOptional() {
        return Optional.ofNullable(resourceId);
    }
}

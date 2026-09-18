package com.builtbyjuls.arat.planning.domain;

import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;

public record Plan(
        UUID planId,
        UUID groupId,
        String title,
        PlanState state,
        UUID createdByAccountId,
        long version,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {

    public Plan {
        Objects.requireNonNull(planId, "planId must not be null");
        Objects.requireNonNull(groupId, "groupId must not be null");
        if (title == null || title.isBlank() || title.length() > 120) {
            throw new IllegalArgumentException("title must be between 1 and 120 characters");
        }
        Objects.requireNonNull(state, "state must not be null");
        Objects.requireNonNull(createdByAccountId, "createdByAccountId must not be null");
        if (version < 1) {
            throw new IllegalArgumentException("version must be positive");
        }
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        Objects.requireNonNull(updatedAt, "updatedAt must not be null");
    }
}

package com.builtbyjuls.arat.groups.domain;

import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;

public record Group(
        UUID groupId,
        String name,
        String description,
        GroupStatus status,
        UUID createdByAccountId,
        long version,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {

    public Group {
        Objects.requireNonNull(groupId, "groupId must not be null");
        if (name == null || name.isBlank() || name.length() > 80) {
            throw new IllegalArgumentException("name must be between 1 and 80 characters");
        }
        if (description == null || description.length() > 500) {
            throw new IllegalArgumentException("description must be at most 500 characters");
        }
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(createdByAccountId, "createdByAccountId must not be null");
        if (version < 1) {
            throw new IllegalArgumentException("version must be positive");
        }
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        Objects.requireNonNull(updatedAt, "updatedAt must not be null");
    }
}

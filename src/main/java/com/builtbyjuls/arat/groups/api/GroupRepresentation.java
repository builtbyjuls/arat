package com.builtbyjuls.arat.groups.api;

import com.builtbyjuls.arat.groups.domain.Group;
import java.time.OffsetDateTime;
import java.util.UUID;

public record GroupRepresentation(
        UUID groupId,
        String name,
        String description,
        long version,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {

    public static GroupRepresentation from(Group group) {
        return new GroupRepresentation(
                group.groupId(),
                group.name(),
                group.description(),
                group.version(),
                group.createdAt(),
                group.updatedAt());
    }
}

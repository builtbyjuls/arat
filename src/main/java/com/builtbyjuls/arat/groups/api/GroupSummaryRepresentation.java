package com.builtbyjuls.arat.groups.api;

import com.builtbyjuls.arat.groups.domain.GroupListing;
import java.util.UUID;

public record GroupSummaryRepresentation(
        UUID groupId,
        String name,
        String description,
        long version,
        String role) {

    public static GroupSummaryRepresentation from(GroupListing listing) {
        return new GroupSummaryRepresentation(
                listing.group().groupId(),
                listing.group().name(),
                listing.group().description(),
                listing.group().version(),
                listing.role().name());
    }
}

package com.builtbyjuls.arat.planning.api;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record ProviderSafeRequestSnapshot(
        UUID requestId,
        long requestVersion,
        String state,
        String distributionMode,
        String category,
        String timeZone,
        String areaCode,
        int radiusKm,
        OffsetDateTime requestedStartsAt,
        OffsetDateTime requestedEndsAt,
        int minimumHeadcount,
        int maximumHeadcount,
        Long budgetMinimumMinorUnits,
        Long budgetMaximumMinorUnits,
        List<String> mustHaves,
        String providerSafeNotes,
        Map<String, Object> categoryAttributes,
        OffsetDateTime offerDeadline,
        OffsetDateTime publishedAt,
        boolean actionable) {

    public ProviderSafeRequestSnapshot {
        mustHaves = List.copyOf(mustHaves);
        categoryAttributes = Map.copyOf(categoryAttributes);
    }
}

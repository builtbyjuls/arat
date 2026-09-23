package com.builtbyjuls.arat.planning.api;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

public record ProviderSafeRequestTerms(
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
        OffsetDateTime offerDeadline) {

    public ProviderSafeRequestTerms {
        mustHaves = List.copyOf(mustHaves);
        categoryAttributes = Map.copyOf(categoryAttributes);
    }
}

package com.builtbyjuls.arat.planning.domain;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record PlanPreference(
        UUID planId,
        UUID accountId,
        long basisPlanVersion,
        Attendance attendance,
        int guestCount,
        Long personalBudgetMinorUnits,
        List<UUID> selectedWindowIds,
        List<String> rankedPreferences,
        String privateNote,
        long version,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {

    public PlanPreference {
        selectedWindowIds = List.copyOf(selectedWindowIds);
        rankedPreferences = List.copyOf(rankedPreferences);
    }
}

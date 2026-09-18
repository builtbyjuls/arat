package com.builtbyjuls.arat.planning.domain;

import java.time.ZoneId;
import java.util.Objects;
import java.util.UUID;

public record RequirementDraft(
        UUID planId,
        ActivityCategory category,
        String timeZone,
        String areaCode,
        int radiusKm,
        int minimumHeadcount,
        int maximumHeadcount,
        Long budgetMinimumMinorUnits,
        Long budgetMaximumMinorUnits,
        String providerSafeNotes,
        String categoryAttributes) {

    public RequirementDraft {
        Objects.requireNonNull(planId, "planId must not be null");
        Objects.requireNonNull(category, "category must not be null");
        if (timeZone == null || timeZone.isBlank() || timeZone.length() > 64) {
            throw new IllegalArgumentException("timeZone must be between 1 and 64 characters");
        }
        try {
            if (!ZoneId.getAvailableZoneIds().contains(timeZone)) {
                throw new IllegalArgumentException("timeZone must be an IANA time zone");
            }
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("timeZone must be an IANA time zone", exception);
        }
        if (areaCode == null || areaCode.isBlank() || areaCode.length() > 64) {
            throw new IllegalArgumentException("areaCode must be between 1 and 64 characters");
        }
        if (radiusKm < 1 || radiusKm > 100) {
            throw new IllegalArgumentException("radiusKm must be between 1 and 100");
        }
        if (minimumHeadcount < 1 || maximumHeadcount > 100 || minimumHeadcount > maximumHeadcount) {
            throw new IllegalArgumentException("headcount must be between 1 and 100");
        }
        if ((budgetMinimumMinorUnits == null) != (budgetMaximumMinorUnits == null)) {
            throw new IllegalArgumentException("budget range must be wholly present or absent");
        }
        if (budgetMinimumMinorUnits != null
                && (budgetMinimumMinorUnits < 0 || budgetMaximumMinorUnits > 100000000
                || budgetMinimumMinorUnits > budgetMaximumMinorUnits)) {
            throw new IllegalArgumentException("budget range is invalid");
        }
        if (providerSafeNotes != null && providerSafeNotes.length() > 1000) {
            throw new IllegalArgumentException("providerSafeNotes must be at most 1000 characters");
        }
        Objects.requireNonNull(categoryAttributes, "categoryAttributes must not be null");
    }
}

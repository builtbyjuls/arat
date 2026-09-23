package com.builtbyjuls.arat.planning.domain;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record RequirementFinalization(
        UUID finalizationId,
        UUID planId,
        long basisPlanVersion,
        UUID selectedCandidateWindowId,
        OffsetDateTime selectedStartsAt,
        OffsetDateTime selectedEndsAt,
        OffsetDateTime offerDeadline,
        ActivityCategory category,
        String timeZone,
        String areaCode,
        int radiusKm,
        int minimumHeadcount,
        int maximumHeadcount,
        Long budgetMinimumMinorUnits,
        Long budgetMaximumMinorUnits,
        List<String> mustHaves,
        String providerSafeNotes,
        String categoryAttributes,
        int currentPreferenceCount,
        int stalePreferenceCount,
        List<FinalizationWarning> warnings,
        UUID finalizedByAccountId,
        OffsetDateTime createdAt) {

    public RequirementFinalization {
        Objects.requireNonNull(finalizationId, "finalizationId must not be null");
        Objects.requireNonNull(planId, "planId must not be null");
        if (basisPlanVersion < 1) {
            throw new IllegalArgumentException("basisPlanVersion must be positive");
        }
        Objects.requireNonNull(selectedCandidateWindowId, "selectedCandidateWindowId must not be null");
        Objects.requireNonNull(selectedStartsAt, "selectedStartsAt must not be null");
        Objects.requireNonNull(selectedEndsAt, "selectedEndsAt must not be null");
        Objects.requireNonNull(offerDeadline, "offerDeadline must not be null");
        if (!selectedStartsAt.isBefore(selectedEndsAt) || !offerDeadline.isBefore(selectedStartsAt)) {
            throw new IllegalArgumentException("offerDeadline must be before selectedStartsAt");
        }
        Objects.requireNonNull(category, "category must not be null");
        validateTimeZone(timeZone);
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
        mustHaves = List.copyOf(mustHaves);
        if (mustHaves.size() > 20 || new HashSet<>(mustHaves).size() != mustHaves.size()
                || mustHaves.stream().anyMatch(value -> value == null || value.isBlank()
                || value.length() > 120)) {
            throw new IllegalArgumentException("mustHaves must contain at most 20 unique values");
        }
        if (providerSafeNotes != null && providerSafeNotes.length() > 1000) {
            throw new IllegalArgumentException("providerSafeNotes must be at most 1000 characters");
        }
        Objects.requireNonNull(categoryAttributes, "categoryAttributes must not be null");
        if (currentPreferenceCount < 0 || stalePreferenceCount < 0) {
            throw new IllegalArgumentException("preference counts must not be negative");
        }
        warnings = List.copyOf(warnings);
        var expectedWarnings = new java.util.ArrayList<FinalizationWarning>();
        if (currentPreferenceCount == 0) {
            expectedWarnings.add(FinalizationWarning.NO_CURRENT_PREFERENCE_INPUT);
        }
        if (stalePreferenceCount > 0) {
            expectedWarnings.add(FinalizationWarning.STALE_PREFERENCE_INPUT_PRESENT);
        }
        if (!warnings.equals(expectedWarnings)) {
            throw new IllegalArgumentException("warnings must match preference counts");
        }
        Objects.requireNonNull(finalizedByAccountId, "finalizedByAccountId must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
    }

    private static void validateTimeZone(String timeZone) {
        if (timeZone == null || timeZone.isBlank() || timeZone.length() > 64) {
            throw new IllegalArgumentException("timeZone must be between 1 and 64 characters");
        }
        if (!ZoneId.getAvailableZoneIds().contains(timeZone)) {
            throw new IllegalArgumentException("timeZone must be an IANA time zone");
        }
    }
}

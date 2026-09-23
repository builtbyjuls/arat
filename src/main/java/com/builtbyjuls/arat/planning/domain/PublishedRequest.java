package com.builtbyjuls.arat.planning.domain;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record PublishedRequest(
        UUID requestId,
        UUID planId,
        long requestVersion,
        PublishedRequestState state,
        RequestDistributionMode distributionMode,
        ActivityCategory category,
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
        String categoryAttributes,
        OffsetDateTime offerDeadline,
        UUID publishedByAccountId,
        OffsetDateTime publishedAt,
        OffsetDateTime closedAt) {

    public PublishedRequest {
        Objects.requireNonNull(requestId, "requestId must not be null");
        Objects.requireNonNull(planId, "planId must not be null");
        if (requestVersion < 1) {
            throw new IllegalArgumentException("requestVersion must be positive");
        }
        Objects.requireNonNull(state, "state must not be null");
        Objects.requireNonNull(distributionMode, "distributionMode must not be null");
        Objects.requireNonNull(category, "category must not be null");
        validateTimeZone(timeZone);
        if (areaCode == null || areaCode.isBlank() || areaCode.length() > 64) {
            throw new IllegalArgumentException("areaCode must be between 1 and 64 characters");
        }
        if (radiusKm < 1 || radiusKm > 100) {
            throw new IllegalArgumentException("radiusKm must be between 1 and 100");
        }
        Objects.requireNonNull(requestedStartsAt, "requestedStartsAt must not be null");
        Objects.requireNonNull(requestedEndsAt, "requestedEndsAt must not be null");
        Objects.requireNonNull(offerDeadline, "offerDeadline must not be null");
        Objects.requireNonNull(publishedAt, "publishedAt must not be null");
        if (!publishedAt.isBefore(offerDeadline)
                || !offerDeadline.isBefore(requestedStartsAt)
                || !requestedStartsAt.isBefore(requestedEndsAt)) {
            throw new IllegalArgumentException("publishedAt, offerDeadline, and requested window must be ordered");
        }
        if (minimumHeadcount < 1 || maximumHeadcount > 100 || minimumHeadcount > maximumHeadcount) {
            throw new IllegalArgumentException("headcount must be between 1 and 100");
        }
        if ((budgetMinimumMinorUnits == null) != (budgetMaximumMinorUnits == null)) {
            throw new IllegalArgumentException("budget range must be wholly present or absent");
        }
        if (budgetMinimumMinorUnits != null
                && (budgetMinimumMinorUnits < 0
                || budgetMaximumMinorUnits > 100000000
                || budgetMinimumMinorUnits > budgetMaximumMinorUnits)) {
            throw new IllegalArgumentException("budget range is invalid");
        }
        mustHaves = List.copyOf(mustHaves);
        if (mustHaves.size() > 20
                || new HashSet<>(mustHaves).size() != mustHaves.size()
                || mustHaves.stream().anyMatch(value -> value.isBlank() || value.length() > 120)) {
            throw new IllegalArgumentException("mustHaves must contain at most 20 unique values");
        }
        if (providerSafeNotes != null && providerSafeNotes.length() > 1000) {
            throw new IllegalArgumentException("providerSafeNotes must be at most 1000 characters");
        }
        Objects.requireNonNull(categoryAttributes, "categoryAttributes must not be null");
        Objects.requireNonNull(publishedByAccountId, "publishedByAccountId must not be null");
        if ((state == PublishedRequestState.OPEN) != (closedAt == null)) {
            throw new IllegalArgumentException("only an open request can omit closedAt");
        }
        if (closedAt != null && closedAt.isBefore(publishedAt)) {
            throw new IllegalArgumentException("closedAt must not be before publishedAt");
        }
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

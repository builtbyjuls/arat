package com.builtbyjuls.arat.planning.domain;

import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;

public record CandidateWindow(
        UUID candidateWindowId,
        UUID planId,
        int sortOrder,
        OffsetDateTime startsAt,
        OffsetDateTime endsAt,
        OffsetDateTime retiredAt) {

    public CandidateWindow {
        Objects.requireNonNull(candidateWindowId, "candidateWindowId must not be null");
        Objects.requireNonNull(planId, "planId must not be null");
        if (sortOrder < 1) {
            throw new IllegalArgumentException("sortOrder must be positive");
        }
        Objects.requireNonNull(startsAt, "startsAt must not be null");
        Objects.requireNonNull(endsAt, "endsAt must not be null");
        if (!startsAt.isBefore(endsAt)) {
            throw new IllegalArgumentException("startsAt must be before endsAt");
        }
    }
}

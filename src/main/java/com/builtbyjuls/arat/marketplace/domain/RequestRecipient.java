package com.builtbyjuls.arat.marketplace.domain;

import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;

public record RequestRecipient(
        UUID publishedRequestId,
        UUID providerId,
        long providerEligibilityVersion,
        RequestRecipientSource source,
        UUID sourceListingId,
        RequestRecipientAccessState accessState,
        OffsetDateTime createdAt) {

    public RequestRecipient {
        Objects.requireNonNull(publishedRequestId, "publishedRequestId must not be null");
        Objects.requireNonNull(providerId, "providerId must not be null");
        if (providerEligibilityVersion < 1) {
            throw new IllegalArgumentException("providerEligibilityVersion must be positive");
        }
        Objects.requireNonNull(source, "source must not be null");
        if (source != RequestRecipientSource.MATCH_RULE || sourceListingId != null) {
            throw new IllegalArgumentException("M2 recipients must use MATCH_RULE without a listing reference");
        }
        Objects.requireNonNull(accessState, "accessState must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
    }
}

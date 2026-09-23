package com.builtbyjuls.arat.planning.api;

import java.time.OffsetDateTime;

public record PlanRequestCancellation(
        PlanRepresentation plan,
        ProviderSafeRequestSnapshot cancelledRequest,
        OffsetDateTime cancelledAt) {
}

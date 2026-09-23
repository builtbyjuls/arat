package com.builtbyjuls.arat.planning.api;

import java.time.OffsetDateTime;

public record RequestClosureTransition(
        long planVersion,
        ProviderSafeRequestSnapshot request,
        OffsetDateTime closedAt) {
}

package com.builtbyjuls.arat.planning.api;

public record PlanRequestCancellation(
        long planVersion,
        ProviderSafeRequestSnapshot cancelledRequest) {
}

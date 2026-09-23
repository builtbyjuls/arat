package com.builtbyjuls.arat.planning.api;

public record RequestVersionTransition(
        long planVersion,
        ProviderSafeRequestSnapshot request,
        ProviderSafeRequestSnapshot previousRequest) {
}

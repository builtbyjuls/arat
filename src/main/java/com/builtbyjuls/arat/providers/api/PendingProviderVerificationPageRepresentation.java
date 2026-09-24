package com.builtbyjuls.arat.providers.api;

import java.util.List;

public record PendingProviderVerificationPageRepresentation(
        List<PendingProviderVerificationRepresentation> items, String nextCursor) {

    public PendingProviderVerificationPageRepresentation {
        items = List.copyOf(items);
    }
}

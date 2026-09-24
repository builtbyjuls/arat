package com.builtbyjuls.arat.providers.api;

import java.util.List;

public record ProviderPageRepresentation(List<ProviderSummaryRepresentation> items, String nextCursor) {

    public ProviderPageRepresentation {
        items = List.copyOf(items);
    }
}

package com.builtbyjuls.arat.marketplace.api;

import java.util.List;

public record ProviderRequestFeedRepresentation(List<PublishedRequestRepresentation> items, String nextCursor) {

    public ProviderRequestFeedRepresentation {
        items = List.copyOf(items);
    }
}

package com.builtbyjuls.arat.planning.api;

import java.util.List;

public record PublishedRequestPageRepresentation(List<GroupPublishedRequestRepresentation> items, String nextCursor) {
    public PublishedRequestPageRepresentation {
        items = List.copyOf(items);
    }
}

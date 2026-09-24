package com.builtbyjuls.arat.groups.api;

import java.util.List;

public record GroupPageRepresentation(List<GroupSummaryRepresentation> items, String nextCursor) {

    public GroupPageRepresentation {
        items = List.copyOf(items);
    }
}

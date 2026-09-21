package com.builtbyjuls.arat.planning.api;

import java.util.List;

public record PlanPageRepresentation(List<PlanSummaryRepresentation> items, String nextCursor) {

    public PlanPageRepresentation {
        items = List.copyOf(items);
    }
}

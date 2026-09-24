package com.builtbyjuls.arat.planning.api;

import java.util.List;

public record RequirementFinalizationPageRepresentation(
        List<RequirementFinalizationRepresentation> items, String nextCursor) {

    public RequirementFinalizationPageRepresentation {
        items = List.copyOf(items);
    }
}

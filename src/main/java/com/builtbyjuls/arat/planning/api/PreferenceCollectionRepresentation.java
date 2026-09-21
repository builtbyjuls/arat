package com.builtbyjuls.arat.planning.api;

import java.util.List;

public record PreferenceCollectionRepresentation(List<GroupPreferenceRepresentation> items) {
    public PreferenceCollectionRepresentation {
        items = List.copyOf(items);
    }
}

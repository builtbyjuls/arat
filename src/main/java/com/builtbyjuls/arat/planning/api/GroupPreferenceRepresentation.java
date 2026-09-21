package com.builtbyjuls.arat.planning.api;

import com.builtbyjuls.arat.planning.domain.PlanPreference;

public record GroupPreferenceRepresentation(PreferenceRepresentation preference, boolean current) {

    public static GroupPreferenceRepresentation from(PlanPreference preference, long currentPlanVersion) {
        return new GroupPreferenceRepresentation(
                PreferenceRepresentation.from(preference), preference.basisPlanVersion() == currentPlanVersion);
    }
}

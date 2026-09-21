package com.builtbyjuls.arat.planning.api;

import com.builtbyjuls.arat.planning.domain.Plan;
import java.util.UUID;

public record PlanSummaryRepresentation(UUID planId, String title, String state, long version) {

    public static PlanSummaryRepresentation from(Plan plan) {
        return new PlanSummaryRepresentation(plan.planId(), plan.title(), plan.state().name(), plan.version());
    }
}

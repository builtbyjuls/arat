package com.builtbyjuls.arat.planning.api;

import com.builtbyjuls.arat.planning.domain.Plan;
import java.util.UUID;

public record PlanRepresentation(
        UUID planId,
        String state,
        UUID createdByAccountId,
        long version) {

    public static PlanRepresentation from(Plan plan) {
        return new PlanRepresentation(
                plan.planId(), plan.state().name(), plan.createdByAccountId(), plan.version());
    }
}

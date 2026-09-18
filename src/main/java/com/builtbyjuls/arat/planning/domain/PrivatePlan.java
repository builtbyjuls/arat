package com.builtbyjuls.arat.planning.domain;

import java.util.List;
import java.util.Objects;

public record PrivatePlan(
        Plan plan,
        RequirementDraft requirementDraft,
        List<CandidateWindow> candidateWindows,
        List<String> mustHaves) {

    public PrivatePlan {
        Objects.requireNonNull(plan, "plan must not be null");
        Objects.requireNonNull(requirementDraft, "requirementDraft must not be null");
        candidateWindows = List.copyOf(candidateWindows);
        mustHaves = List.copyOf(mustHaves);
    }
}

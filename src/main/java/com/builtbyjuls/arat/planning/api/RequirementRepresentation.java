package com.builtbyjuls.arat.planning.api;

import com.builtbyjuls.arat.planning.domain.CandidateWindow;
import com.builtbyjuls.arat.planning.domain.PrivatePlan;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@Schema(name = "PlanRequirements")
public record RequirementRepresentation(
        String title,
        String category,
        String timeZone,
        List<CandidateWindowRepresentation> candidateWindows,
        AreaRepresentation area,
        HeadcountRepresentation headcount,
        BudgetRepresentation budget,
        List<String> mustHaves,
        String providerSafeNotes,
        Map<String, Object> categoryAttributes) {

    public RequirementRepresentation {
        candidateWindows = List.copyOf(candidateWindows);
        mustHaves = List.copyOf(mustHaves);
        categoryAttributes = Map.copyOf(categoryAttributes);
    }

    public static RequirementRepresentation from(PrivatePlan privatePlan, ObjectMapper objectMapper) {
        var plan = privatePlan.plan();
        var draft = privatePlan.requirementDraft();
        Map<String, Object> categoryAttributes;
        try {
            categoryAttributes = objectMapper.readValue(draft.categoryAttributes(), new TypeReference<>() {});
        } catch (tools.jackson.core.JacksonException exception) {
            throw new IllegalStateException("stored category attributes are invalid", exception);
        }
        var budget = draft.budgetMinimumMinorUnits() == null ? null : new BudgetRepresentation(
                "PHP", amount(draft.budgetMinimumMinorUnits()), amount(draft.budgetMaximumMinorUnits()));
        return new RequirementRepresentation(
                plan.title(),
                draft.category().name(),
                draft.timeZone(),
                privatePlan.candidateWindows().stream().map(CandidateWindowRepresentation::from).toList(),
                new AreaRepresentation(draft.areaCode(), draft.radiusKm()),
                new HeadcountRepresentation(draft.minimumHeadcount(), draft.maximumHeadcount()),
                budget,
                privatePlan.mustHaves(),
                draft.providerSafeNotes(),
                categoryAttributes);
    }

    private static String amount(long minorUnits) {
        return BigDecimal.valueOf(minorUnits, 2).toPlainString();
    }

    public record CandidateWindowRepresentation(UUID id, OffsetDateTime startAt, OffsetDateTime endAt) {
        static CandidateWindowRepresentation from(CandidateWindow candidateWindow) {
            return new CandidateWindowRepresentation(
                    candidateWindow.candidateWindowId(), candidateWindow.startsAt(), candidateWindow.endsAt());
        }
    }

    public record AreaRepresentation(String code, int radiusKm) {}

    public record HeadcountRepresentation(int minimum, int maximum) {}

    public record BudgetRepresentation(String currency, String minimumAmount, String maximumAmount) {}
}

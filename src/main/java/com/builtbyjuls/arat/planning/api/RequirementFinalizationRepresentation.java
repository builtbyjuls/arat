package com.builtbyjuls.arat.planning.api;

import com.builtbyjuls.arat.planning.domain.RequirementFinalization;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@Schema(name = "RequirementFinalization")
public record RequirementFinalizationRepresentation(
        UUID finalizationId, long basisPlanVersion, UUID candidateWindowId,
        OffsetDateTime selectedStartAt, OffsetDateTime selectedEndAt, OffsetDateTime offerDeadline,
        String category, String timeZone, RequirementRepresentation.AreaRepresentation area,
        RequirementRepresentation.HeadcountRepresentation headcount, RequirementRepresentation.BudgetRepresentation budget,
        List<String> mustHaves, String providerSafeNotes, Map<String, Object> categoryAttributes,
        int currentPreferenceCount, int stalePreferenceCount, List<String> warnings) {
    public RequirementFinalizationRepresentation {
        mustHaves = List.copyOf(mustHaves);
        categoryAttributes = Map.copyOf(categoryAttributes);
        warnings = List.copyOf(warnings);
    }
    public static RequirementFinalizationRepresentation from(RequirementFinalization value, ObjectMapper objectMapper) {
        try {
            Map<String, Object> attributes = objectMapper.readValue(value.categoryAttributes(), new TypeReference<>() {});
            var budget = value.budgetMinimumMinorUnits() == null ? null : new RequirementRepresentation.BudgetRepresentation("PHP", amount(value.budgetMinimumMinorUnits()), amount(value.budgetMaximumMinorUnits()));
            return new RequirementFinalizationRepresentation(value.finalizationId(), value.basisPlanVersion(), value.selectedCandidateWindowId(), value.selectedStartsAt(), value.selectedEndsAt(), value.offerDeadline(), value.category().name(), value.timeZone(), new RequirementRepresentation.AreaRepresentation(value.areaCode(), value.radiusKm()), new RequirementRepresentation.HeadcountRepresentation(value.minimumHeadcount(), value.maximumHeadcount()), budget, value.mustHaves(), value.providerSafeNotes(), attributes, value.currentPreferenceCount(), value.stalePreferenceCount(), value.warnings().stream().map(Enum::name).toList());
        } catch (tools.jackson.core.JacksonException exception) {
            throw new IllegalStateException("stored finalization attributes are invalid", exception);
        }
    }
    private static String amount(long minorUnits) { return BigDecimal.valueOf(minorUnits, 2).toPlainString(); }
}

package com.builtbyjuls.arat.planning.api;

import com.builtbyjuls.arat.planning.domain.ActivityCategory;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

public record RequirementReplacementRequest(
        @NotBlank @Size(max = 120) String title,
        @NotNull ActivityCategory category,
        @NotBlank @Size(max = 64) String timeZone,
        @NotNull @NotEmpty @Size(max = 10) List<@NotNull @Valid CandidateWindowRequest> candidateWindows,
        @NotNull @Valid CreatePlanRequest.AreaRequest area,
        @NotNull @Valid CreatePlanRequest.HeadcountRequest headcount,
        @Valid CreatePlanRequest.BudgetRequest budget,
        @NotNull @Size(max = 20) List<@NotBlank @Size(max = 120) String> mustHaves,
        @Size(max = 1000) String providerSafeNotes,
        @NotNull @Size(max = 20) Map<String, JsonNode> categoryAttributes) {

    private static final String ATTRIBUTE_KEY_PATTERN = "[a-z][A-Za-z0-9]{0,39}";

    @AssertTrue(message = "timeZone must be an IANA time zone")
    @Schema(hidden = true)
    public boolean isValidTimeZone() {
        return timeZone != null && ZoneId.getAvailableZoneIds().contains(timeZone);
    }

    @AssertTrue(message = "mustHaves must contain unique values")
    @Schema(hidden = true)
    public boolean isUniqueMustHaves() {
        return mustHaves != null && new HashSet<>(mustHaves).size() == mustHaves.size();
    }

    @AssertTrue(message = "candidateWindows must not repeat retained IDs")
    @Schema(hidden = true)
    public boolean isUniqueCandidateWindowIds() {
        if (candidateWindows == null) {
            return false;
        }
        var ids = candidateWindows.stream()
                .filter(java.util.Objects::nonNull)
                .map(CandidateWindowRequest::id)
                .filter(java.util.Objects::nonNull)
                .toList();
        return new HashSet<>(ids).size() == ids.size();
    }

    @AssertTrue(message = "categoryAttributes must use bounded scalar values")
    @Schema(hidden = true)
    public boolean isValidCategoryAttributes() {
        if (categoryAttributes == null || categoryAttributes.size() > 20) {
            return false;
        }
        return categoryAttributes.entrySet().stream().allMatch(entry -> {
            var key = entry.getKey();
            var value = entry.getValue();
            if (key == null || !key.matches(ATTRIBUTE_KEY_PATTERN) || value == null) {
                return false;
            }
            if (value.isBoolean()) {
                return true;
            }
            if (value.isIntegralNumber()) {
                return value.canConvertToLong() && value.longValue() >= 0 && value.longValue() <= 1_000_000;
            }
            return value.isTextual() && value.textValue().length() >= 1 && value.textValue().length() <= 120;
        });
    }

    @Schema(name = "RequirementReplacementCandidateWindow")
    public record CandidateWindowRequest(
            UUID id,
            @NotNull OffsetDateTime startAt,
            @NotNull OffsetDateTime endAt) {

        @AssertTrue(message = "startAt must be before endAt")
        @Schema(hidden = true)
        public boolean isValidInterval() {
            return startAt != null && endAt != null && startAt.isBefore(endAt);
        }
    }
}

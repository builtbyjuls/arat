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
import java.util.Set;
import tools.jackson.databind.JsonNode;

public record CreatePlanRequest(
        @NotBlank @Size(max = 120) String title,
        @NotNull ActivityCategory category,
        @NotBlank @Size(max = 64) String timeZone,
        @NotNull @NotEmpty @Size(max = 10) List<@NotNull @Valid CandidateWindowRequest> candidateWindows,
        @NotNull @Valid AreaRequest area,
        @NotNull @Valid HeadcountRequest headcount,
        @Valid BudgetRequest budget,
        @NotNull @Size(max = 20) List<@NotBlank @Size(max = 120) String> mustHaves,
        @Size(max = 1000) String providerSafeNotes,
        @NotNull @Size(max = 20) Map<String, JsonNode> categoryAttributes) {

    private static final String ATTRIBUTE_KEY_PATTERN = "[a-z][A-Za-z0-9]{0,39}";
    private static final BigDecimal MAX_AMOUNT = new BigDecimal("1000000.00");

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

    public record CandidateWindowRequest(
            @NotNull OffsetDateTime startAt,
            @NotNull OffsetDateTime endAt) {

        @AssertTrue(message = "startAt must be before endAt")
        @Schema(hidden = true)
        public boolean isValidInterval() {
            return startAt != null && endAt != null && startAt.isBefore(endAt);
        }
    }

    public record AreaRequest(
            @NotBlank @Size(max = 64) String code,
            @NotNull @Min(1) @Max(100) Integer radiusKm) {
    }

    public record HeadcountRequest(
            @NotNull @Min(1) @Max(100) Integer minimum,
            @NotNull @Min(1) @Max(100) Integer maximum) {

        @AssertTrue(message = "minimum must not exceed maximum")
        @Schema(hidden = true)
        public boolean isValidRange() {
            return minimum != null && maximum != null && minimum <= maximum;
        }
    }

    public record BudgetRequest(
            @NotBlank String currency,
            @NotBlank @Pattern(regexp = "(0|[1-9][0-9]{0,6})\\.[0-9]{2}") String minimumAmount,
            @NotBlank @Pattern(regexp = "(0|[1-9][0-9]{0,6})\\.[0-9]{2}") String maximumAmount) {

        @AssertTrue(message = "budget must be PHP with a valid range")
        @Schema(hidden = true)
        public boolean isValidRange() {
            if (!"PHP".equals(currency) || minimumAmount == null || maximumAmount == null) {
                return false;
            }
            try {
                var minimum = new BigDecimal(minimumAmount);
                var maximum = new BigDecimal(maximumAmount);
                return minimum.compareTo(BigDecimal.ZERO) >= 0
                        && maximum.compareTo(MAX_AMOUNT) <= 0
                        && minimum.compareTo(maximum) <= 0;
            } catch (NumberFormatException exception) {
                return false;
            }
        }

        public long minimumMinorUnits() {
            return new BigDecimal(minimumAmount).movePointRight(2).longValueExact();
        }

        public long maximumMinorUnits() {
            return new BigDecimal(maximumAmount).movePointRight(2).longValueExact();
        }
    }

}

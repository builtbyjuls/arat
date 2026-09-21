package com.builtbyjuls.arat.planning.api;

import com.builtbyjuls.arat.planning.domain.Attendance;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

@Schema(name = "CreatePreferenceRequest")
public record CreatePreferenceRequest(
        @NotNull @Min(1) Long basisPlanVersion,
        @NotNull Attendance attendance,
        @NotNull @Min(0) @Max(20) Integer guestCount,
        @NotNull List<@NotNull UUID> selectedWindowIds,
        @Valid PersonalBudgetRequest personalBudget,
        @NotNull @Size(max = 10) List<@NotBlank @Size(max = 80) String> rankedPreferences,
        @Size(max = 1000) String privateNote) {

    private static final BigDecimal MAX_AMOUNT = new BigDecimal("1000000.00");

    @AssertTrue(message = "guestCount must be zero when attendance is NOT_JOINING")
    @Schema(hidden = true)
    public boolean isGuestCountValidForAttendance() {
        return attendance != Attendance.NOT_JOINING || guestCount == null || guestCount == 0;
    }

    @AssertTrue(message = "selectedWindowIds must be unique")
    @Schema(hidden = true)
    public boolean isSelectedWindowIdsUnique() {
        return selectedWindowIds != null && new HashSet<>(selectedWindowIds).size() == selectedWindowIds.size();
    }

    @AssertTrue(message = "rankedPreferences must be unique and trimmed")
    @Schema(hidden = true)
    public boolean isRankedPreferencesUniqueAndTrimmed() {
        return rankedPreferences != null && rankedPreferences.stream().allMatch(value -> value != null && value.equals(value.trim()))
                && new HashSet<>(rankedPreferences).size() == rankedPreferences.size();
    }

    public record PersonalBudgetRequest(
            @NotBlank String currency,
            @NotBlank @Pattern(regexp = "(0|[1-9][0-9]{0,6})\\.[0-9]{2}") String amount) {

        @AssertTrue(message = "personalBudget must be PHP within the supported range")
        @Schema(hidden = true)
        public boolean isValidAmount() {
            if (!"PHP".equals(currency) || amount == null) {
                return false;
            }
            try {
                var value = new BigDecimal(amount);
                return value.compareTo(BigDecimal.ZERO) >= 0 && value.compareTo(MAX_AMOUNT) <= 0;
            } catch (NumberFormatException exception) {
                return false;
            }
        }

        public long minorUnits() {
            return new BigDecimal(amount).movePointRight(2).longValueExact();
        }
    }
}

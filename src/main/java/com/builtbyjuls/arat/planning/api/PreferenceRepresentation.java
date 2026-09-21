package com.builtbyjuls.arat.planning.api;

import com.builtbyjuls.arat.planning.domain.Attendance;
import com.builtbyjuls.arat.planning.domain.PlanPreference;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@Schema(name = "PlanPreference")
public record PreferenceRepresentation(
        UUID accountId,
        long basisPlanVersion,
        Attendance attendance,
        int guestCount,
        List<UUID> selectedWindowIds,
        PersonalBudgetRepresentation personalBudget,
        List<String> rankedPreferences,
        String privateNote,
        long version) {

    public static PreferenceRepresentation from(PlanPreference preference) {
        return new PreferenceRepresentation(
                preference.accountId(), preference.basisPlanVersion(),
                preference.attendance(), preference.guestCount(), preference.selectedWindowIds(),
                preference.personalBudgetMinorUnits() == null ? null : PersonalBudgetRepresentation.fromMinorUnits(preference.personalBudgetMinorUnits()),
                preference.rankedPreferences(), preference.privateNote(), preference.version());
    }

    @Schema(name = "PersonalBudget")
    public record PersonalBudgetRepresentation(String currency, String amount) {
        static PersonalBudgetRepresentation fromMinorUnits(long minorUnits) {
            return new PersonalBudgetRepresentation("PHP", BigDecimal.valueOf(minorUnits, 2).toPlainString());
        }
    }
}

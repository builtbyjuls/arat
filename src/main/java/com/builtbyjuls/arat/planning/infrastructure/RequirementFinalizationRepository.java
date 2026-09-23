package com.builtbyjuls.arat.planning.infrastructure;

import com.builtbyjuls.arat.planning.domain.ActivityCategory;
import com.builtbyjuls.arat.planning.domain.FinalizationWarning;
import com.builtbyjuls.arat.planning.domain.RequirementFinalization;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class RequirementFinalizationRepository {

    private final JdbcClient jdbcClient;

    public RequirementFinalizationRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public RequirementFinalization insert(RequirementFinalization finalization) {
        return jdbcClient.sql("""
                        INSERT INTO planning_requirement_finalization (
                            finalization_id, plan_id, basis_plan_version, selected_candidate_window_id,
                            selected_starts_at, selected_ends_at, offer_deadline, category, time_zone,
                            area_code, radius_km, minimum_headcount, maximum_headcount, budget_currency,
                            budget_minimum_minor_units, budget_maximum_minor_units, must_haves,
                            provider_safe_notes, category_attributes, current_preference_count,
                            stale_preference_count, warnings, finalized_by_account_id, created_at
                        )
                        VALUES (
                            :finalizationId, :planId, :basisPlanVersion, :selectedCandidateWindowId,
                            :selectedStartsAt, :selectedEndsAt, :offerDeadline, :category, :timeZone,
                            :areaCode, :radiusKm, :minimumHeadcount, :maximumHeadcount, :budgetCurrency,
                            :budgetMinimumMinorUnits, :budgetMaximumMinorUnits, CAST(:mustHaves AS varchar[]),
                            :providerSafeNotes, CAST(:categoryAttributes AS jsonb), :currentPreferenceCount,
                            :stalePreferenceCount, CAST(:warnings AS varchar[]), :finalizedByAccountId, :createdAt
                        )
                        RETURNING finalization_id, plan_id, basis_plan_version, selected_candidate_window_id,
                                  selected_starts_at, selected_ends_at, offer_deadline, category, time_zone,
                                  area_code, radius_km, minimum_headcount, maximum_headcount,
                                  budget_minimum_minor_units, budget_maximum_minor_units, must_haves,
                                  provider_safe_notes, category_attributes::text AS category_attributes,
                                  current_preference_count, stale_preference_count, warnings,
                                  finalized_by_account_id, created_at
                        """)
                .param("finalizationId", finalization.finalizationId())
                .param("planId", finalization.planId())
                .param("basisPlanVersion", finalization.basisPlanVersion())
                .param("selectedCandidateWindowId", finalization.selectedCandidateWindowId())
                .param("selectedStartsAt", finalization.selectedStartsAt())
                .param("selectedEndsAt", finalization.selectedEndsAt())
                .param("offerDeadline", finalization.offerDeadline())
                .param("category", finalization.category().name())
                .param("timeZone", finalization.timeZone())
                .param("areaCode", finalization.areaCode())
                .param("radiusKm", finalization.radiusKm())
                .param("minimumHeadcount", finalization.minimumHeadcount())
                .param("maximumHeadcount", finalization.maximumHeadcount())
                .param("budgetCurrency", finalization.budgetMinimumMinorUnits() == null ? null : "PHP")
                .param("budgetMinimumMinorUnits", finalization.budgetMinimumMinorUnits())
                .param("budgetMaximumMinorUnits", finalization.budgetMaximumMinorUnits())
                .param("mustHaves", finalization.mustHaves().toArray(String[]::new))
                .param("providerSafeNotes", finalization.providerSafeNotes())
                .param("categoryAttributes", finalization.categoryAttributes())
                .param("currentPreferenceCount", finalization.currentPreferenceCount())
                .param("stalePreferenceCount", finalization.stalePreferenceCount())
                .param("warnings", finalization.warnings().stream().map(Enum::name).toArray(String[]::new))
                .param("finalizedByAccountId", finalization.finalizedByAccountId())
                .param("createdAt", finalization.createdAt())
                .query(this::mapFinalization)
                .single();
    }

    @Transactional(readOnly = true)
    public Optional<RequirementFinalization> findById(UUID finalizationId) {
        return jdbcClient.sql(selectFinalization() + " WHERE finalization_id = :finalizationId")
                .param("finalizationId", finalizationId)
                .query(this::mapFinalization)
                .optional();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public OffsetDateTime databaseDecisionTime() {
        return jdbcClient.sql("SELECT clock_timestamp()")
                .query(OffsetDateTime.class)
                .single();
    }

    private String selectFinalization() {
        return """
                SELECT finalization_id, plan_id, basis_plan_version, selected_candidate_window_id,
                       selected_starts_at, selected_ends_at, offer_deadline, category, time_zone,
                       area_code, radius_km, minimum_headcount, maximum_headcount,
                       budget_minimum_minor_units, budget_maximum_minor_units, must_haves,
                       provider_safe_notes, category_attributes::text AS category_attributes,
                       current_preference_count, stale_preference_count, warnings,
                       finalized_by_account_id, created_at
                FROM planning_requirement_finalization
                """;
    }

    private RequirementFinalization mapFinalization(ResultSet resultSet, int rowNum) throws SQLException {
        return new RequirementFinalization(
                resultSet.getObject("finalization_id", UUID.class),
                resultSet.getObject("plan_id", UUID.class),
                resultSet.getLong("basis_plan_version"),
                resultSet.getObject("selected_candidate_window_id", UUID.class),
                resultSet.getObject("selected_starts_at", OffsetDateTime.class),
                resultSet.getObject("selected_ends_at", OffsetDateTime.class),
                resultSet.getObject("offer_deadline", OffsetDateTime.class),
                ActivityCategory.valueOf(resultSet.getString("category")),
                resultSet.getString("time_zone"),
                resultSet.getString("area_code"),
                resultSet.getInt("radius_km"),
                resultSet.getInt("minimum_headcount"),
                resultSet.getInt("maximum_headcount"),
                resultSet.getObject("budget_minimum_minor_units", Long.class),
                resultSet.getObject("budget_maximum_minor_units", Long.class),
                array(resultSet, "must_haves", String.class),
                resultSet.getString("provider_safe_notes"),
                resultSet.getString("category_attributes"),
                resultSet.getInt("current_preference_count"),
                resultSet.getInt("stale_preference_count"),
                array(resultSet, "warnings", String.class).stream().map(FinalizationWarning::valueOf).toList(),
                resultSet.getObject("finalized_by_account_id", UUID.class),
                resultSet.getObject("created_at", OffsetDateTime.class));
    }

    private <T> List<T> array(ResultSet resultSet, String column, Class<T> type) throws SQLException {
        var value = resultSet.getArray(column);
        if (value == null) {
            return List.of();
        }
        return Arrays.stream((Object[]) value.getArray()).map(type::cast).toList();
    }
}

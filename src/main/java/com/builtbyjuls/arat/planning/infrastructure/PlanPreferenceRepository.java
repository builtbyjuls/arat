package com.builtbyjuls.arat.planning.infrastructure;

import com.builtbyjuls.arat.planning.domain.Attendance;
import com.builtbyjuls.arat.planning.domain.PlanPreference;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class PlanPreferenceRepository {

    private final JdbcClient jdbcClient;

    public PlanPreferenceRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<PlanPreference> insert(PlanPreference preference) {
        var inserted = jdbcClient.sql("""
                        INSERT INTO planning_plan_preference (
                            plan_id, account_id, basis_plan_version, attendance, guest_count,
                            personal_budget_currency, personal_budget_minor_units, private_note, version,
                            created_at, updated_at
                        )
                        VALUES (
                            :planId, :accountId, :basisPlanVersion, :attendance, :guestCount,
                            :personalBudgetCurrency, :personalBudgetMinorUnits, :privateNote, 1,
                            statement_timestamp(), statement_timestamp()
                        )
                        ON CONFLICT (plan_id, account_id) DO NOTHING
                        RETURNING plan_id, account_id, basis_plan_version, attendance, guest_count,
                                  personal_budget_minor_units, private_note, version, created_at, updated_at
                        """)
                .param("planId", preference.planId())
                .param("accountId", preference.accountId())
                .param("basisPlanVersion", preference.basisPlanVersion())
                .param("attendance", preference.attendance().name())
                .param("guestCount", preference.guestCount())
                .param("personalBudgetCurrency", preference.personalBudgetMinorUnits() == null ? null : "PHP")
                .param("personalBudgetMinorUnits", preference.personalBudgetMinorUnits())
                .param("privateNote", preference.privateNote())
                .query(this::mapPreferenceWithoutCollections)
                .optional();
        if (inserted.isEmpty()) {
            return Optional.empty();
        }
        replaceSelectedWindows(preference);
        replaceRankedPreferences(preference);
        return inserted.map(row -> withCollections(row, preference.selectedWindowIds(), preference.rankedPreferences()));
    }

    @Transactional(readOnly = true)
    public Optional<PlanPreference> find(UUID planId, UUID accountId) {
        return query(planId, accountId).optional();
    }

    @Transactional(readOnly = true)
    public List<PlanPreference> findAllByPlanId(UUID planId) {
        return jdbcClient.sql(baseSelect() + " WHERE p.plan_id = :planId ORDER BY p.account_id")
                .param("planId", planId)
                .query(this::mapPreference)
                .list();
    }

    private JdbcClient.MappedQuerySpec<PlanPreference> query(UUID planId, UUID accountId) {
        return jdbcClient.sql(baseSelect() + " WHERE p.plan_id = :planId AND p.account_id = :accountId")
                .param("planId", planId)
                .param("accountId", accountId)
                .query(this::mapPreference);
    }

    private String baseSelect() {
        return """
                SELECT p.plan_id, p.account_id, p.basis_plan_version, p.attendance, p.guest_count,
                       p.personal_budget_minor_units, p.private_note, p.version, p.created_at, p.updated_at,
                       ARRAY(SELECT candidate_window_id
                             FROM planning_preference_selected_window
                             WHERE plan_id = p.plan_id AND account_id = p.account_id
                             ORDER BY sort_order) AS selected_window_ids,
                       ARRAY(SELECT preference_text
                             FROM planning_preference_ranked_item
                             WHERE plan_id = p.plan_id AND account_id = p.account_id
                             ORDER BY sort_order) AS ranked_preferences
                FROM planning_plan_preference p
                """;
    }

    private void replaceSelectedWindows(PlanPreference preference) {
        for (var index = 0; index < preference.selectedWindowIds().size(); index++) {
            jdbcClient.sql("""
                            INSERT INTO planning_preference_selected_window (
                                plan_id, account_id, candidate_window_id, sort_order
                            ) VALUES (:planId, :accountId, :candidateWindowId, :sortOrder)
                            """)
                    .param("planId", preference.planId())
                    .param("accountId", preference.accountId())
                    .param("candidateWindowId", preference.selectedWindowIds().get(index))
                    .param("sortOrder", index + 1)
                    .update();
        }
    }

    private void replaceRankedPreferences(PlanPreference preference) {
        for (var index = 0; index < preference.rankedPreferences().size(); index++) {
            jdbcClient.sql("""
                            INSERT INTO planning_preference_ranked_item (
                                plan_id, account_id, preference_text, sort_order
                            ) VALUES (:planId, :accountId, :preferenceText, :sortOrder)
                            """)
                    .param("planId", preference.planId())
                    .param("accountId", preference.accountId())
                    .param("preferenceText", preference.rankedPreferences().get(index))
                    .param("sortOrder", index + 1)
                    .update();
        }
    }

    private PlanPreference mapPreference(ResultSet resultSet, int rowNum) throws SQLException {
        return withCollections(
                mapPreferenceWithoutCollections(resultSet, rowNum),
                array(resultSet, "selected_window_ids", UUID.class),
                array(resultSet, "ranked_preferences", String.class));
    }

    private PlanPreference mapPreferenceWithoutCollections(ResultSet resultSet, int rowNum) throws SQLException {
        var budget = resultSet.getLong("personal_budget_minor_units");
        var hasBudget = !resultSet.wasNull();
        return new PlanPreference(
                resultSet.getObject("plan_id", UUID.class),
                resultSet.getObject("account_id", UUID.class),
                resultSet.getLong("basis_plan_version"),
                Attendance.valueOf(resultSet.getString("attendance")),
                resultSet.getInt("guest_count"),
                hasBudget ? budget : null,
                List.of(),
                List.of(),
                resultSet.getString("private_note"),
                resultSet.getLong("version"),
                resultSet.getObject("created_at", OffsetDateTime.class),
                resultSet.getObject("updated_at", OffsetDateTime.class));
    }

    private PlanPreference withCollections(PlanPreference preference, List<UUID> selectedWindowIds, List<String> rankedPreferences) {
        return new PlanPreference(
                preference.planId(), preference.accountId(), preference.basisPlanVersion(), preference.attendance(),
                preference.guestCount(), preference.personalBudgetMinorUnits(), selectedWindowIds, rankedPreferences,
                preference.privateNote(), preference.version(), preference.createdAt(), preference.updatedAt());
    }

    @SuppressWarnings("unchecked")
    private <T> List<T> array(ResultSet resultSet, String column, Class<T> type) throws SQLException {
        var value = resultSet.getArray(column);
        if (value == null) {
            return List.of();
        }
        var values = (Object[]) value.getArray();
        return java.util.Arrays.stream(values).map(type::cast).toList();
    }
}

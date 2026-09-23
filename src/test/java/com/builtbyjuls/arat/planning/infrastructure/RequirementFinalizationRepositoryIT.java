package com.builtbyjuls.arat.planning.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.builtbyjuls.arat.PostgreSqlIntegrationTest;
import com.builtbyjuls.arat.planning.domain.ActivityCategory;
import com.builtbyjuls.arat.planning.domain.FinalizationWarning;
import com.builtbyjuls.arat.planning.domain.RequirementFinalization;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(properties = "arat.test.planning-context=true")
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class RequirementFinalizationRepositoryIT extends PostgreSqlIntegrationTest {

    private static final UUID OWNER_ID = UUID.fromString("30000000-0000-4000-8000-000000000021");
    private static final UUID GROUP_ID = UUID.fromString("40000000-0000-4000-8000-000000000021");
    private static final UUID PLAN_ID = UUID.fromString("50000000-0000-4000-8000-000000000021");
    private static final UUID OTHER_PLAN_ID = UUID.fromString("50000000-0000-4000-8000-000000000022");
    private static final UUID WINDOW_ID = UUID.fromString("60000000-0000-4000-8000-000000000021");
    private static final UUID OTHER_WINDOW_ID = UUID.fromString("60000000-0000-4000-8000-000000000022");
    private static final UUID FINALIZATION_ID = UUID.fromString("70000000-0000-4000-8000-000000000021");
    private static final OffsetDateTime STARTS_AT = OffsetDateTime.parse("2027-01-09T09:00:00Z");

    @Autowired
    private RequirementFinalizationRepository repository;

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private TransactionTemplate transactionTemplate;

    @BeforeEach
    void prepareDatabase() {
        transactionTemplate = new TransactionTemplate(transactionManager);
        jdbcClient.sql("TRUNCATE TABLE planning_requirement_finalization").update();
        jdbcClient.sql("DELETE FROM planning_requirement_must_have").update();
        jdbcClient.sql("DELETE FROM planning_candidate_window").update();
        jdbcClient.sql("DELETE FROM planning_requirement_draft").update();
        jdbcClient.sql("DELETE FROM planning_plan").update();
        jdbcClient.sql("DELETE FROM group_membership").update();
        jdbcClient.sql("DELETE FROM group_account").update();
        jdbcClient.sql("DELETE FROM identity_account WHERE account_id = :ownerId")
                .param("ownerId", OWNER_ID)
                .update();
        jdbcClient.sql("INSERT INTO identity_account (account_id, display_name) VALUES (:ownerId, 'Finalization owner')")
                .param("ownerId", OWNER_ID)
                .update();
        jdbcClient.sql("""
                        INSERT INTO group_account (group_id, name, description, status, created_by_account_id)
                        VALUES (:groupId, 'Finalization group', '', 'ACTIVE', :ownerId)
                        """)
                .param("groupId", GROUP_ID)
                .param("ownerId", OWNER_ID)
                .update();
        jdbcClient.sql("""
                        INSERT INTO planning_plan (plan_id, group_id, title, state, created_by_account_id)
                        VALUES (:planId, :groupId, 'Finalization plan', 'COLLABORATING', :ownerId)
                        """)
                .param("planId", PLAN_ID)
                .param("groupId", GROUP_ID)
                .param("ownerId", OWNER_ID)
                .update();
        jdbcClient.sql("""
                        INSERT INTO planning_candidate_window (
                            candidate_window_id, plan_id, sort_order, starts_at, ends_at
                        )
                        VALUES (:windowId, :planId, 1, :startsAt, :endsAt)
                        """)
                .param("windowId", WINDOW_ID)
                .param("planId", PLAN_ID)
                .param("startsAt", STARTS_AT)
                .param("endsAt", STARTS_AT.plusHours(2))
                .update();
        jdbcClient.sql("""
                        INSERT INTO planning_plan (plan_id, group_id, title, state, created_by_account_id)
                        VALUES (:planId, :groupId, 'Other finalization plan', 'COLLABORATING', :ownerId)
                        """)
                .param("planId", OTHER_PLAN_ID)
                .param("groupId", GROUP_ID)
                .param("ownerId", OWNER_ID)
                .update();
        jdbcClient.sql("""
                        INSERT INTO planning_candidate_window (
                            candidate_window_id, plan_id, sort_order, starts_at, ends_at
                        )
                        VALUES (:windowId, :planId, 1, :startsAt, :endsAt)
                        """)
                .param("windowId", OTHER_WINDOW_ID)
                .param("planId", OTHER_PLAN_ID)
                .param("startsAt", STARTS_AT)
                .param("endsAt", STARTS_AT.plusHours(2))
                .update();
    }

    @Test
    void persistsAndReadsAnImmutableProviderPublishableSnapshot() {
        var finalization = finalization(FINALIZATION_ID);

        var stored = inTransaction(() -> repository.insert(finalization));

        assertThat(repository.findById(FINALIZATION_ID)).contains(stored);
        assertThat(stored.mustHaves()).containsExactly(" parking ", "shower");
        assertThat(stored.budgetMinimumMinorUnits()).isEqualTo(0L);
        assertThat(stored.budgetMaximumMinorUnits()).isEqualTo(250000L);
        assertThat(stored.selectedStartsAt()).isEqualTo(STARTS_AT);
        assertThat(stored.offerDeadline()).isEqualTo(STARTS_AT.minusDays(1));
        assertThat(stored.categoryAttributes()).isEqualTo("{\"courtCount\": 2, \"hasParking\": true}");
        assertThat(jdbcClient.sql("""
                        SELECT jsonb_typeof(category_attributes -> 'hasParking')
                        FROM planning_requirement_finalization
                        WHERE finalization_id = :finalizationId
                        """)
                .param("finalizationId", FINALIZATION_ID)
                .query(String.class)
                .single()).isEqualTo("boolean");
        assertThat(jdbcClient.sql("""
                        SELECT jsonb_typeof(category_attributes -> 'courtCount')
                        FROM planning_requirement_finalization
                        WHERE finalization_id = :finalizationId
                        """)
                .param("finalizationId", FINALIZATION_ID)
                .query(String.class)
                .single()).isEqualTo("number");
    }

    @Test
    void rejectsInvalidSnapshotFactsInPostgreSql() {
        inTransaction(() -> repository.insert(finalization(FINALIZATION_ID)));

        assertRejected("basis_plan_version", "0");
        assertRejected("selected_candidate_window_id", "'60000000-0000-4000-8000-000000000022'::uuid");
        assertRejected("selected_ends_at", "source.selected_starts_at");
        assertRejected("offer_deadline", "source.selected_starts_at");
        assertRejected("category", "'NOT_A_CATEGORY'");
        assertRejected("time_zone", "''");
        assertRejected("time_zone", "repeat('x', 65)");
        assertRejected("area_code", "' '");
        assertRejected("area_code", "repeat('x', 65)");
        assertRejected("radius_km", "0");
        assertRejected("radius_km", "101");
        assertRejected("minimum_headcount", "0");
        assertRejected("maximum_headcount", "3");
        assertRejected("maximum_headcount", "101");
        assertRejected("budget_currency", "NULL");
        assertRejected("budget_currency", "'USD'");
        assertRejected("budget_minimum_minor_units", "NULL");
        assertRejected("budget_maximum_minor_units", "NULL");
        assertRejected("budget_minimum_minor_units", "-1");
        assertRejected("budget_maximum_minor_units", "100000001");
        assertRejected("budget_minimum_minor_units", "250001");
        assertRejected("must_haves", "ARRAY['parking', 'parking']::varchar[]");
        assertRejected("must_haves", "ARRAY['parking', NULL]::varchar[]");
        assertRejected("must_haves", "ARRAY['   ']::varchar[]");
        assertRejected("must_haves", "ARRAY[repeat('x', 121)]::varchar[]");
        assertRejected("must_haves", "ARRAY(SELECT 'must-' || value FROM generate_series(1, 21) AS value)::varchar[]");
        assertRejected("provider_safe_notes", "repeat('x', 1001)");
        assertRejected("category_attributes", "'[]'::jsonb");
        assertRejected("category_attributes", "'{\"bad_key\": true}'::jsonb");
        assertRejected("current_preference_count", "-1");
        assertRejected(Map.of("stale_preference_count", "-1", "warnings", "'{}'::varchar[]"));
        assertRejected("warnings", "ARRAY['NO_CURRENT_PREFERENCE_INPUT']::varchar[]");
        assertRejected("warnings", "ARRAY['UNKNOWN_WARNING']::varchar[]");
        assertRejected("warnings", "ARRAY['STALE_PREFERENCE_INPUT_PRESENT', 'STALE_PREFERENCE_INPUT_PRESENT']::varchar[]");
        assertRejected("finalized_by_account_id", "'30000000-0000-4000-8000-000000000099'::uuid");
    }

    @Test
    void rejectsDirectSnapshotMutationWhileAllowingAnotherInsert() {
        inTransaction(() -> repository.insert(finalization(FINALIZATION_ID)));

        assertThatThrownBy(() -> jdbcClient.sql("""
                        UPDATE planning_requirement_finalization
                        SET provider_safe_notes = 'Changed after finalization.'
                        WHERE finalization_id = :finalizationId
                        """)
                .param("finalizationId", FINALIZATION_ID)
                .update()).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbcClient.sql("DELETE FROM planning_requirement_finalization WHERE finalization_id = :finalizationId")
                .param("finalizationId", FINALIZATION_ID)
                .update()).isInstanceOf(DataAccessException.class);

        var another = finalization(UUID.fromString("70000000-0000-4000-8000-000000000022"));
        assertThat(inTransaction(() -> repository.insert(another)).finalizationId()).isEqualTo(another.finalizationId());
    }

    private void assertRejected(String replacedColumn, String replacement) {
        assertRejected(Map.of(replacedColumn, replacement));
    }

    private void assertRejected(Map<String, String> replacements) {
        assertThatThrownBy(() -> insertCopy(UUID.randomUUID(), replacements))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private void insertCopy(UUID finalizationId, Map<String, String> replacements) {
        var columns = List.of(
                "plan_id", "basis_plan_version", "selected_candidate_window_id", "selected_starts_at",
                "selected_ends_at", "offer_deadline", "category", "time_zone", "area_code", "radius_km",
                "minimum_headcount", "maximum_headcount", "budget_currency", "budget_minimum_minor_units",
                "budget_maximum_minor_units", "must_haves", "provider_safe_notes", "category_attributes",
                "current_preference_count", "stale_preference_count", "warnings", "finalized_by_account_id", "created_at");
        var selectedColumns = columns.stream()
                .map(column -> replacements.getOrDefault(column, "source." + column))
                .collect(Collectors.joining(", "));
        jdbcClient.sql("""
                        INSERT INTO planning_requirement_finalization (finalization_id, %s)
                        SELECT :finalizationId, %s
                        FROM planning_requirement_finalization source
                        WHERE source.finalization_id = :sourceFinalizationId
                        """.formatted(String.join(", ", columns), selectedColumns))
                .param("finalizationId", finalizationId)
                .param("sourceFinalizationId", FINALIZATION_ID)
                .update();
    }

    private RequirementFinalization finalization(UUID finalizationId) {
        return new RequirementFinalization(
                finalizationId,
                PLAN_ID,
                1,
                WINDOW_ID,
                STARTS_AT,
                STARTS_AT.plusHours(2),
                STARTS_AT.minusDays(1),
                ActivityCategory.COURT,
                "Asia/Manila",
                "BGC",
                5,
                4,
                10,
                0L,
                250000L,
                List.of(" parking ", "shower"),
                "Indoor court preferred.",
                "{\"hasParking\":true,\"courtCount\":2}",
                2,
                1,
                List.of(FinalizationWarning.STALE_PREFERENCE_INPUT_PRESENT),
                OWNER_ID,
                OffsetDateTime.parse("2026-09-18T06:00:00Z"));
    }

    private <T> T inTransaction(java.util.concurrent.Callable<T> work) {
        return transactionTemplate.execute(status -> {
            try {
                return work.call();
            } catch (RuntimeException exception) {
                throw exception;
            } catch (Exception exception) {
                throw new IllegalStateException(exception);
            }
        });
    }
}

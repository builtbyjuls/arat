package com.builtbyjuls.arat.planning.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.builtbyjuls.arat.PostgreSqlIntegrationTest;
import com.builtbyjuls.arat.planning.domain.ActivityCategory;
import com.builtbyjuls.arat.planning.domain.PublishedRequest;
import com.builtbyjuls.arat.planning.domain.PublishedRequestState;
import com.builtbyjuls.arat.planning.domain.RequestDistributionMode;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(properties = "arat.test.planning-context=true")
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PublishedRequestRepositoryIT extends PostgreSqlIntegrationTest {

    private static final UUID OWNER_ID = UUID.fromString("30000000-0000-4000-8000-000000000031");
    private static final UUID GROUP_ID = UUID.fromString("40000000-0000-4000-8000-000000000031");
    private static final OffsetDateTime PUBLISHED_AT = OffsetDateTime.parse("2026-09-23T01:00:00Z");
    private static final OffsetDateTime STARTS_AT = OffsetDateTime.parse("2027-02-06T10:00:00Z");

    @Autowired
    private PublishedRequestRepository repository;

    @Autowired
    private PlanRepository planRepository;

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private DataSource dataSource;

    private TransactionTemplate transactionTemplate;

    @BeforeEach
    void prepareOwnerAndGroup() {
        transactionTemplate = new TransactionTemplate(transactionManager);
        jdbcClient.sql("""
                        INSERT INTO identity_account (account_id, display_name)
                        VALUES (:ownerId, 'Request owner')
                        ON CONFLICT (account_id) DO NOTHING
                        """)
                .param("ownerId", OWNER_ID)
                .update();
        jdbcClient.sql("""
                        INSERT INTO group_account (group_id, name, description, status, created_by_account_id)
                        VALUES (:groupId, 'Request group', '', 'ACTIVE', :ownerId)
                        ON CONFLICT (group_id) DO NOTHING
                        """)
                .param("groupId", GROUP_ID)
                .param("ownerId", OWNER_ID)
                .update();
    }

    @Test
    void persistsAndReadsTheImmutableOrderedSnapshotAndPlanPointer() {
        var planId = createPlan("Persisted request");
        var request = request(UUID.randomUUID(), planId, 1);

        var stored = insertCurrent(request);

        assertThat(repository.findById(request.requestId())).contains(stored);
        assertThat(repository.findCurrentByPlanId(planId)).contains(stored);
        assertThat(stored.mustHaves()).containsExactly(" parking ", "shower");
        assertThat(stored.categoryAttributes()).isEqualTo("{\"courtCount\": 2, \"hasParking\": true}");
        assertThat(jdbcClient.sql("""
                        SELECT jsonb_typeof(category_attributes -> 'courtCount')
                        FROM planning_published_request
                        WHERE request_id = :requestId
                        """)
                .param("requestId", request.requestId())
                .query(String.class)
                .single()).isEqualTo("number");

        var plan = inTransaction(() -> planRepository.lockPlan(planId));
        assertThat(plan.state().name()).isEqualTo("OPEN_FOR_OFFERS");
        assertThat(plan.currentRequestId()).isEqualTo(request.requestId());
    }

    @Test
    void rejectsSnapshotMutationAndDeletionButAllowsOneTerminalLifecycleChange() {
        var planId = createPlan("Immutable request");
        var request = request(UUID.randomUUID(), planId, 1);
        insertCurrent(request);

        assertThatThrownBy(() -> jdbcClient.sql("""
                        UPDATE planning_published_request
                        SET provider_safe_notes = 'Changed after publication.'
                        WHERE request_id = :requestId
                        """)
                .param("requestId", request.requestId())
                .update()).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbcClient.sql("""
                        UPDATE planning_published_request
                        SET must_haves = ARRAY['changed']::varchar[]
                        WHERE request_id = :requestId
                        """)
                .param("requestId", request.requestId())
                .update()).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbcClient.sql("""
                        UPDATE planning_published_request
                        SET category_attributes = '{"changed": true}'::jsonb
                        WHERE request_id = :requestId
                        """)
                .param("requestId", request.requestId())
                .update()).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbcClient.sql("""
                        DELETE FROM planning_published_request WHERE request_id = :requestId
                        """)
                .param("requestId", request.requestId())
                .update()).isInstanceOf(DataAccessException.class);

        inTransaction(() -> {
            jdbcClient.sql("""
                            UPDATE planning_published_request
                            SET state = 'CLOSED', closed_at = clock_timestamp()
                            WHERE request_id = :requestId
                            """)
                    .param("requestId", request.requestId())
                    .update();
            jdbcClient.sql("""
                            UPDATE planning_plan
                            SET state = 'COLLABORATING', current_request_id = NULL
                            WHERE plan_id = :planId
                            """)
                    .param("planId", planId)
                    .update();
            return null;
        });

        assertThat(repository.findById(request.requestId()).orElseThrow().state())
                .isEqualTo(PublishedRequestState.CLOSED);
        assertThat(repository.findCurrentByPlanId(planId)).isEmpty();
        assertThatThrownBy(() -> jdbcClient.sql("""
                        UPDATE planning_published_request
                        SET state = 'CANCELLED'
                        WHERE request_id = :requestId
                        """)
                .param("requestId", request.requestId())
                .update()).isInstanceOf(DataAccessException.class);
    }

    @ParameterizedTest
    @EnumSource(value = PublishedRequestState.class, names = {"SUPERSEDED", "CLOSED", "CANCELLED"})
    void allowsEachM2TerminalLifecycleState(PublishedRequestState terminalState) {
        var planId = createPlan("Terminal request " + terminalState.name());
        var request = request(UUID.randomUUID(), planId, 1);
        insertCurrent(request);

        inTransaction(() -> {
            jdbcClient.sql("""
                            UPDATE planning_published_request
                            SET state = :state, closed_at = clock_timestamp()
                            WHERE request_id = :requestId
                            """)
                    .param("state", terminalState.name())
                    .param("requestId", request.requestId())
                    .update();
            if (terminalState == PublishedRequestState.SUPERSEDED) {
                var replacement = request(UUID.randomUUID(), planId, 2);
                repository.insert(replacement);
                jdbcClient.sql("""
                                UPDATE planning_plan
                                SET current_request_id = :requestId
                                WHERE plan_id = :planId
                                """)
                        .param("requestId", replacement.requestId())
                        .param("planId", planId)
                        .update();
            } else {
                jdbcClient.sql("""
                                UPDATE planning_plan
                                SET state = :planState, current_request_id = NULL
                                WHERE plan_id = :planId
                                """)
                        .param("planState", terminalState == PublishedRequestState.CANCELLED
                                ? "CANCELLED"
                                : "COLLABORATING")
                        .param("planId", planId)
                        .update();
            }
            return null;
        });

        assertThat(repository.findById(request.requestId()).orElseThrow().state()).isEqualTo(terminalState);
    }

    @Test
    void enforcesVersionDistributionDeadlineAndSnapshotConstraints() {
        var currentPlanId = createPlan("Current request");
        var currentRequest = request(UUID.randomUUID(), currentPlanId, 1);
        insertCurrent(currentRequest);
        var historyPlanId = createPlan("Request history");

        assertCopyRejected(currentRequest.requestId(), historyPlanId, Map.of("request_version", "0"));
        assertCopyRejected(
                currentRequest.requestId(),
                historyPlanId,
                Map.of("distribution_mode", "'DIRECT_INVITATION'"));
        assertCopyRejected(
                currentRequest.requestId(),
                historyPlanId,
                Map.of("offer_deadline", "source.published_at"));
        assertCopyRejected(
                currentRequest.requestId(),
                historyPlanId,
                Map.of("requested_starts_at", "source.offer_deadline"));
        assertCopyRejected(currentRequest.requestId(), historyPlanId, Map.of("minimum_headcount", "0"));
        assertCopyRejected(currentRequest.requestId(), historyPlanId, Map.of("budget_currency", "NULL"));
        assertCopyRejected(
                currentRequest.requestId(),
                historyPlanId,
                Map.of("must_haves", "ARRAY['parking', 'parking']::varchar[]"));
        assertCopyRejected(
                currentRequest.requestId(),
                historyPlanId,
                Map.of("category_attributes", "'[]'::jsonb"));

        insertTerminalCopy(currentRequest.requestId(), UUID.randomUUID(), historyPlanId, Map.of());
        assertCopyRejected(currentRequest.requestId(), historyPlanId, Map.of());

        assertThatThrownBy(() -> insertCopy(
                currentRequest.requestId(),
                UUID.randomUUID(),
                currentPlanId,
                Map.of("request_version", "2")))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void rejectsCrossPlanAndInvalidStatePointerCombinations() {
        var owningPlanId = createPlan("Owning plan");
        var otherPlanId = createPlan("Other plan");
        var request = request(UUID.randomUUID(), owningPlanId, 1);
        insertCurrent(request);

        assertThatThrownBy(() -> inTransaction(() -> {
            jdbcClient.sql("""
                            UPDATE planning_plan
                            SET state = 'OPEN_FOR_OFFERS', current_request_id = :requestId
                            WHERE plan_id = :planId
                            """)
                    .param("requestId", request.requestId())
                    .param("planId", otherPlanId)
                    .update();
            return null;
        })).isInstanceOfAny(DataAccessException.class, TransactionSystemException.class);

        assertThatThrownBy(() -> jdbcClient.sql("""
                        UPDATE planning_plan
                        SET state = 'COLLABORATING'
                        WHERE plan_id = :planId
                        """)
                .param("planId", owningPlanId)
                .update()).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbcClient.sql("""
                        UPDATE planning_plan
                        SET state = 'OPEN_FOR_OFFERS'
                        WHERE plan_id = :planId
                        """)
                .param("planId", otherPlanId)
                .update()).isInstanceOf(DataAccessException.class);

        assertThatThrownBy(() -> inTransaction(() -> {
            jdbcClient.sql("""
                            UPDATE planning_published_request
                            SET state = 'CLOSED', closed_at = clock_timestamp()
                            WHERE request_id = :requestId
                            """)
                    .param("requestId", request.requestId())
                    .update();
            return null;
        })).isInstanceOfAny(DataAccessException.class, TransactionSystemException.class);
        assertThat(repository.findById(request.requestId()).orElseThrow().state())
                .isEqualTo(PublishedRequestState.OPEN);
    }

    @Test
    void retainsExistingPlanStatesWithNullPointers() {
        var collaboratingPlanId = createPlan("Existing collaborating plan");
        var cancelledPlanId = createPlan("Existing cancelled plan");
        jdbcClient.sql("UPDATE planning_plan SET state = 'CANCELLED' WHERE plan_id = :planId")
                .param("planId", cancelledPlanId)
                .update();

        assertThat(planState(collaboratingPlanId)).isEqualTo("COLLABORATING");
        assertThat(currentRequestId(collaboratingPlanId)).isNull();
        assertThat(planState(cancelledPlanId)).isEqualTo("CANCELLED");
        assertThat(currentRequestId(cancelledPlanId)).isNull();
    }

    @Test
    void migrationPreservesExistingPlanRowsAndAddsNullPointers() {
        var schema = "m2t15_" + UUID.randomUUID().toString().replace("-", "");
        jdbcClient.sql("CREATE SCHEMA " + schema).update();
        try {
            migrationFor(schema, "015").migrate();
            var migrationJdbc = JdbcClient.create(dataSource);
            var ownerId = UUID.randomUUID();
            var groupId = UUID.randomUUID();
            var collaboratingPlanId = UUID.randomUUID();
            var cancelledPlanId = UUID.randomUUID();
            var createdAt = OffsetDateTime.parse("2026-09-22T03:00:00Z");
            migrationJdbc.sql("""
                            INSERT INTO %s.identity_account (account_id, display_name)
                            VALUES (:ownerId, 'Migration owner')
                            """.formatted(schema))
                    .param("ownerId", ownerId)
                    .update();
            migrationJdbc.sql("""
                            INSERT INTO %s.group_account (
                                group_id, name, description, status, created_by_account_id
                            )
                            VALUES (:groupId, 'Migration group', '', 'ACTIVE', :ownerId)
                            """.formatted(schema))
                    .param("groupId", groupId)
                    .param("ownerId", ownerId)
                    .update();
            migrationJdbc.sql("""
                            INSERT INTO %s.planning_plan (
                                plan_id, group_id, title, state, created_by_account_id,
                                version, created_at, updated_at
                            )
                            VALUES
                                (:collaboratingPlanId, :groupId, 'Existing collaborating', 'COLLABORATING',
                                 :ownerId, 7, :createdAt, :createdAt),
                                (:cancelledPlanId, :groupId, 'Existing cancelled', 'CANCELLED',
                                 :ownerId, 9, :createdAt, :createdAt)
                            """.formatted(schema))
                    .param("collaboratingPlanId", collaboratingPlanId)
                    .param("cancelledPlanId", cancelledPlanId)
                    .param("groupId", groupId)
                    .param("ownerId", ownerId)
                    .param("createdAt", createdAt)
                    .update();

            migrationFor(schema, "016").migrate();

            assertThat(migrationJdbc.sql("""
                            SELECT state || ':' || version || ':' || (current_request_id IS NULL)
                            FROM %s.planning_plan
                            ORDER BY version
                            """.formatted(schema))
                    .query(String.class)
                    .list()).containsExactly("COLLABORATING:7:true", "CANCELLED:9:true");
            assertThat(migrationJdbc.sql("""
                            SELECT count(*)
                            FROM %s.planning_plan
                            WHERE created_at = :createdAt AND updated_at = :createdAt
                            """.formatted(schema))
                    .param("createdAt", createdAt)
                    .query(Long.class)
                    .single()).isEqualTo(2);
        } finally {
            jdbcClient.sql("DROP SCHEMA " + schema + " CASCADE").update();
        }
    }

    private PublishedRequest insertCurrent(PublishedRequest request) {
        return inTransaction(() -> {
            var stored = repository.insert(request);
            jdbcClient.sql("""
                            UPDATE planning_plan
                            SET state = 'OPEN_FOR_OFFERS', current_request_id = :requestId
                            WHERE plan_id = :planId
                            """)
                    .param("requestId", request.requestId())
                    .param("planId", request.planId())
                    .update();
            return stored;
        });
    }

    private UUID createPlan(String title) {
        var planId = UUID.randomUUID();
        jdbcClient.sql("""
                        INSERT INTO planning_plan (plan_id, group_id, title, state, created_by_account_id)
                        VALUES (:planId, :groupId, :title, 'COLLABORATING', :ownerId)
                        """)
                .param("planId", planId)
                .param("groupId", GROUP_ID)
                .param("title", title)
                .param("ownerId", OWNER_ID)
                .update();
        return planId;
    }

    private PublishedRequest request(UUID requestId, UUID planId, long version) {
        return new PublishedRequest(
                requestId,
                planId,
                version,
                PublishedRequestState.OPEN,
                RequestDistributionMode.MATCHED_POOL,
                ActivityCategory.COURT,
                "Asia/Manila",
                "BGC",
                5,
                STARTS_AT,
                STARTS_AT.plusHours(2),
                4,
                10,
                0L,
                250000L,
                List.of(" parking ", "shower"),
                "Indoor court preferred.",
                "{\"hasParking\":true,\"courtCount\":2}",
                STARTS_AT.minusDays(1),
                OWNER_ID,
                PUBLISHED_AT,
                null);
    }

    private void assertCopyRejected(UUID sourceRequestId, UUID planId, Map<String, String> replacements) {
        assertThatThrownBy(() -> insertTerminalCopy(sourceRequestId, UUID.randomUUID(), planId, replacements))
                .isInstanceOf(DataAccessException.class);
    }

    private void insertTerminalCopy(
            UUID sourceRequestId, UUID requestId, UUID planId, Map<String, String> replacements) {
        var terminalReplacements = new LinkedHashMap<String, String>();
        terminalReplacements.put("plan_id", ":planId");
        terminalReplacements.put("state", "'CLOSED'");
        terminalReplacements.put("closed_at", "source.published_at");
        terminalReplacements.putAll(replacements);
        insertCopy(sourceRequestId, requestId, planId, terminalReplacements);
    }

    private void insertCopy(
            UUID sourceRequestId, UUID requestId, UUID planId, Map<String, String> replacements) {
        var columns = List.of(
                "plan_id", "request_version", "state", "distribution_mode", "category", "time_zone",
                "area_code", "radius_km", "requested_starts_at", "requested_ends_at", "minimum_headcount",
                "maximum_headcount", "budget_currency", "budget_minimum_minor_units",
                "budget_maximum_minor_units", "must_haves", "provider_safe_notes", "category_attributes",
                "offer_deadline", "published_by_account_id", "published_at", "closed_at");
        var selectedColumns = columns.stream()
                .map(column -> replacements.getOrDefault(column, "source." + column))
                .collect(Collectors.joining(", "));
        jdbcClient.sql("""
                        INSERT INTO planning_published_request (request_id, %s)
                        SELECT :requestId, %s
                        FROM planning_published_request source
                        WHERE source.request_id = :sourceRequestId
                        """.formatted(String.join(", ", columns), selectedColumns))
                .param("requestId", requestId)
                .param("sourceRequestId", sourceRequestId)
                .param("planId", planId)
                .update();
    }

    private String planState(UUID planId) {
        return jdbcClient.sql("SELECT state FROM planning_plan WHERE plan_id = :planId")
                .param("planId", planId)
                .query(String.class)
                .single();
    }

    private UUID currentRequestId(UUID planId) {
        return jdbcClient.sql("SELECT current_request_id FROM planning_plan WHERE plan_id = :planId")
                .param("planId", planId)
                .query(UUID.class)
                .optional()
                .orElse(null);
    }

    private Flyway migrationFor(String schema, String target) {
        return Flyway.configure()
                .dataSource(dataSource)
                .schemas(schema)
                .defaultSchema(schema)
                .locations("classpath:db/migration")
                .target(MigrationVersion.fromVersion(target))
                .load();
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

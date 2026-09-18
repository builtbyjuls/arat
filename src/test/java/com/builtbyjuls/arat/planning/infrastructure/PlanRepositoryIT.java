package com.builtbyjuls.arat.planning.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.builtbyjuls.arat.PostgreSqlIntegrationTest;
import com.builtbyjuls.arat.planning.domain.ActivityCategory;
import com.builtbyjuls.arat.planning.domain.CandidateWindow;
import com.builtbyjuls.arat.planning.domain.Plan;
import com.builtbyjuls.arat.planning.domain.PlanState;
import com.builtbyjuls.arat.planning.domain.RequirementDraft;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(properties = "arat.test.planning-context=true")
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PlanRepositoryIT extends PostgreSqlIntegrationTest {

    private static final UUID OWNER_ID = UUID.fromString("30000000-0000-4000-8000-000000000001");
    private static final UUID GROUP_ID = UUID.fromString("40000000-0000-4000-8000-000000000001");
    private static final UUID PLAN_ID = UUID.fromString("50000000-0000-4000-8000-000000000001");
    private static final UUID WINDOW_ONE_ID = UUID.fromString("60000000-0000-4000-8000-000000000001");
    private static final UUID WINDOW_TWO_ID = UUID.fromString("60000000-0000-4000-8000-000000000002");

    @Autowired
    private PlanRepository repository;

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private TransactionTemplate transactionTemplate;

    @BeforeEach
    void prepareDatabase() {
        transactionTemplate = new TransactionTemplate(transactionManager);
        jdbcClient.sql("DELETE FROM planning_requirement_must_have").update();
        jdbcClient.sql("DELETE FROM planning_candidate_window").update();
        jdbcClient.sql("DELETE FROM planning_requirement_draft").update();
        jdbcClient.sql("DELETE FROM planning_plan").update();
        jdbcClient.sql("DELETE FROM group_membership").update();
        jdbcClient.sql("DELETE FROM group_account").update();
        jdbcClient.sql("DELETE FROM identity_account WHERE account_id = :ownerId")
                .param("ownerId", OWNER_ID)
                .update();
        jdbcClient.sql("INSERT INTO identity_account (account_id, display_name) VALUES (:ownerId, 'Plan owner')")
                .param("ownerId", OWNER_ID)
                .update();
        jdbcClient.sql("""
                        INSERT INTO group_account (group_id, name, description, status, created_by_account_id)
                        VALUES (:groupId, 'Plan group', '', 'ACTIVE', :ownerId)
                        """)
                .param("groupId", GROUP_ID)
                .param("ownerId", OWNER_ID)
                .update();
    }

    @Test
    void insertsReadsLocksUpdatesAndPagesPrivatePlansInStableOrder() {
        var earlier = OffsetDateTime.parse("2026-09-18T06:00:00Z");
        var later = earlier.plusHours(1);
        var firstPlan = plan(PLAN_ID, "First outing", earlier);
        var secondPlanId = UUID.fromString("50000000-0000-4000-8000-000000000002");
        var secondPlan = plan(secondPlanId, "Second outing", later);

        inTransaction(() -> {
            repository.insert(firstPlan, draft(PLAN_ID), List.of(window(WINDOW_ONE_ID, 1, earlier)), List.of("parking", "shower"));
            repository.insert(secondPlan, draft(secondPlanId), List.of(window(WINDOW_TWO_ID, secondPlanId, 1, later)), List.of("indoor"));
            assertThat(repository.lockPlan(PLAN_ID)).isEqualTo(firstPlan);
            return null;
        });

        var privatePlan = repository.findPrivate(PLAN_ID, GROUP_ID).orElseThrow();
        assertThat(privatePlan.plan()).isEqualTo(firstPlan);
        assertThat(privatePlan.requirementDraft().budgetMinimumMinorUnits()).isEqualTo(0);
        assertThat(privatePlan.candidateWindows()).extracting(CandidateWindow::candidateWindowId).containsExactly(WINDOW_ONE_ID);
        assertThat(privatePlan.mustHaves()).containsExactly("parking", "shower");
        assertThat(repository.findPrivate(PLAN_ID, UUID.randomUUID())).isEmpty();

        assertThat(repository.listPage(GROUP_ID, 1)).extracting(Plan::planId).containsExactly(secondPlanId);
        assertThat(repository.listPage(GROUP_ID, later, secondPlanId, 10)).extracting(Plan::planId).containsExactly(PLAN_ID);

        var updated = inTransaction(() -> repository.updateVersioned(PLAN_ID, 1, "Updated outing"));
        assertThat(updated).isPresent();
        assertThat(updated.orElseThrow().version()).isEqualTo(2);
        assertThat(inTransaction(() -> repository.updateVersioned(PLAN_ID, 1, "Stale outing"))).isEmpty();

        var replacement = new RequirementDraft(
                PLAN_ID, ActivityCategory.KTV, "Asia/Manila", "Makati", 10, 6, 12,
                null, null, null, "{\"hasProjector\":true}");
        var replacementUpdated = inTransaction(
                () -> repository.replaceRequirementDraftVersioned(PLAN_ID, 2, "Updated KTV outing", replacement));
        assertThat(replacementUpdated).isPresent();
        assertThat(replacementUpdated.orElseThrow().version()).isEqualTo(3);
        var replacedDraft = repository.findPrivate(PLAN_ID, GROUP_ID).orElseThrow().requirementDraft();
        assertThat(replacedDraft.category()).isEqualTo(ActivityCategory.KTV);
        assertThat(replacedDraft.areaCode()).isEqualTo("Makati");
        assertThat(replacedDraft.budgetMinimumMinorUnits()).isNull();
        assertThat(replacedDraft.categoryAttributes()).isEqualTo("{\"hasProjector\": true}");
        assertThat(inTransaction(
                () -> repository.replaceRequirementDraftVersioned(PLAN_ID, 2, "Stale KTV outing", draft(PLAN_ID))))
                .isEmpty();
    }

    @Test
    void reconcilesStableCandidateWindowsAndReplacesOrderedMustHaves() {
        var now = OffsetDateTime.parse("2026-09-18T06:00:00Z");
        var retiredWindowId = UUID.fromString("60000000-0000-4000-8000-000000000003");
        inTransaction(() -> {
            repository.insert(
                    plan(PLAN_ID, "Friday outing", now),
                    draft(PLAN_ID),
                    List.of(window(WINDOW_ONE_ID, 1, now), window(retiredWindowId, 2, now.plusHours(3))),
                    List.of("parking", "shower"));
            repository.reconcileCandidateWindows(
                    PLAN_ID,
                    List.of(window(WINDOW_ONE_ID, 2, now.plusDays(1)), window(WINDOW_TWO_ID, 1, now.plusDays(2))));
            repository.replaceMustHaves(PLAN_ID, List.of("aircon", "parking"));
            return null;
        });

        var privatePlan = repository.findPrivate(PLAN_ID, GROUP_ID).orElseThrow();
        assertThat(privatePlan.candidateWindows()).extracting(CandidateWindow::candidateWindowId)
                .containsExactly(WINDOW_TWO_ID, WINDOW_ONE_ID);
        assertThat(privatePlan.mustHaves()).containsExactly("aircon", "parking");
        assertThat(jdbcClient.sql("""
                        SELECT retired_at IS NOT NULL
                        FROM planning_candidate_window
                        WHERE candidate_window_id = :candidateWindowId
                        """)
                .param("candidateWindowId", retiredWindowId)
                .query(Boolean.class)
                .single()).isTrue();
    }

    @Test
    void databaseRejectsInvalidPlanningFactsAndDuplicateChildren() {
        var now = OffsetDateTime.parse("2026-09-18T06:00:00Z");
        assertThatThrownBy(() -> jdbcClient.sql("""
                        INSERT INTO planning_plan (plan_id, group_id, title, state, created_by_account_id)
                        VALUES (:planId, :groupId, 'Bad state', 'DRAFT', :ownerId)
                        """)
                .param("planId", PLAN_ID)
                .param("groupId", GROUP_ID)
                .param("ownerId", OWNER_ID)
                .update()).isInstanceOf(DataIntegrityViolationException.class);

        insertValidPlan(now);
        assertThatThrownBy(() -> jdbcClient.sql("""
                        INSERT INTO planning_requirement_draft (
                            plan_id, category, time_zone, area_code, radius_km, minimum_headcount,
                            maximum_headcount, category_attributes
                        )
                        VALUES (:planId, 'COURT', 'Asia/Manila', 'BGC', 101, 4, 3, '{}'::jsonb)
                        """)
                .param("planId", PLAN_ID)
                .update()).isInstanceOf(DataIntegrityViolationException.class);
        var invalidBudgetPlanId = UUID.fromString("50000000-0000-4000-8000-000000000003");
        jdbcClient.sql("""
                        INSERT INTO planning_plan (plan_id, group_id, title, state, created_by_account_id)
                        VALUES (:planId, :groupId, 'Bad budget', 'COLLABORATING', :ownerId)
                        """)
                .param("planId", invalidBudgetPlanId)
                .param("groupId", GROUP_ID)
                .param("ownerId", OWNER_ID)
                .update();
        assertThatThrownBy(() -> jdbcClient.sql("""
                        INSERT INTO planning_requirement_draft (
                            plan_id, category, time_zone, area_code, radius_km, minimum_headcount,
                            maximum_headcount, budget_currency, budget_minimum_minor_units,
                            budget_maximum_minor_units, category_attributes
                        )
                        VALUES (:planId, 'COURT', 'Asia/Manila', 'BGC', 5, 4, 10, 'PHP', 101, 100, '{}'::jsonb)
                        """)
                .param("planId", invalidBudgetPlanId)
                .update()).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbcClient.sql("""
                        INSERT INTO planning_requirement_draft (
                            plan_id, category, time_zone, area_code, radius_km, minimum_headcount,
                            maximum_headcount, budget_currency, budget_minimum_minor_units,
                            budget_maximum_minor_units, category_attributes
                        )
                        VALUES (:planId, 'COURT', 'Asia/Manila', 'BGC', 5, 4, 10, NULL, 0, 100, '{}'::jsonb)
                        """)
                .param("planId", invalidBudgetPlanId)
                .update()).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbcClient.sql("""
                        INSERT INTO planning_requirement_draft (
                            plan_id, category, time_zone, area_code, radius_km, minimum_headcount,
                            maximum_headcount, budget_currency, budget_minimum_minor_units,
                            budget_maximum_minor_units, category_attributes
                        )
                        VALUES (:planId, 'COURT', 'Asia/Manila', 'BGC', 5, 4, 10, 'PHP', NULL, 100, '{}'::jsonb)
                        """)
                .param("planId", invalidBudgetPlanId)
                .update()).isInstanceOf(DataIntegrityViolationException.class);

        jdbcClient.sql("""
                        INSERT INTO planning_requirement_draft (
                            plan_id, category, time_zone, area_code, radius_km, minimum_headcount,
                            maximum_headcount, budget_currency, budget_minimum_minor_units,
                            budget_maximum_minor_units, category_attributes
                        )
                        VALUES (:planId, 'COURT', 'Asia/Manila', 'BGC', 5, 4, 10, 'PHP', 0, 100, '{}'::jsonb)
                        """)
                .param("planId", PLAN_ID)
                .update();
        assertThatThrownBy(() -> jdbcClient.sql("""
                        INSERT INTO planning_requirement_draft (
                            plan_id, category, time_zone, area_code, radius_km, minimum_headcount,
                            maximum_headcount, category_attributes
                        )
                        VALUES (:planId, 'COURT', 'Asia/Manila', 'BGC', 5, 4, 10, '{}'::jsonb)
                        """)
                .param("planId", PLAN_ID)
                .update()).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbcClient.sql("""
                        INSERT INTO planning_candidate_window (
                            candidate_window_id, plan_id, sort_order, starts_at, ends_at
                        )
                        VALUES (:candidateWindowId, :planId, 1, :endAt, :startAt)
                        """)
                .param("candidateWindowId", WINDOW_ONE_ID)
                .param("planId", PLAN_ID)
                .param("startAt", now)
                .param("endAt", now.plusHours(1))
                .update()).isInstanceOf(DataIntegrityViolationException.class);
        jdbcClient.sql("""
                        INSERT INTO planning_candidate_window (
                            candidate_window_id, plan_id, sort_order, starts_at, ends_at
                        )
                        VALUES (:candidateWindowId, :planId, 1, :startAt, :endAt)
                        """)
                .param("candidateWindowId", WINDOW_ONE_ID)
                .param("planId", PLAN_ID)
                .param("startAt", now)
                .param("endAt", now.plusHours(1))
                .update();
        assertThatThrownBy(() -> jdbcClient.sql("""
                        INSERT INTO planning_candidate_window (
                            candidate_window_id, plan_id, sort_order, starts_at, ends_at
                        )
                        VALUES (:candidateWindowId, :planId, 1, :startAt, :endAt)
                        """)
                .param("candidateWindowId", WINDOW_TWO_ID)
                .param("planId", PLAN_ID)
                .param("startAt", now.plusDays(1))
                .param("endAt", now.plusDays(1).plusHours(1))
                .update()).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rollsBackPlanAndDraftTogether() {
        var now = OffsetDateTime.parse("2026-09-18T06:00:00Z");
        assertThatThrownBy(() -> inTransaction(() -> {
            repository.insert(plan(PLAN_ID, "Rolled back outing", now), draft(PLAN_ID), List.of(window(WINDOW_ONE_ID, 1, now)), List.of());
            throw new IllegalStateException("force rollback");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(repository.findPrivate(PLAN_ID, GROUP_ID)).isEmpty();
        assertThat(jdbcClient.sql("SELECT count(*) FROM planning_requirement_draft WHERE plan_id = :planId")
                .param("planId", PLAN_ID)
                .query(Long.class)
                .single()).isZero();
    }

    @Test
    void rejectsFixedOffsetTimeZones() {
        assertThatThrownBy(() -> new RequirementDraft(
                PLAN_ID, ActivityCategory.COURT, "+08:00", "BGC", 5, 4, 10,
                null, null, null, "{}"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private void insertValidPlan(OffsetDateTime now) {
        jdbcClient.sql("""
                        INSERT INTO planning_plan (
                            plan_id, group_id, title, state, created_by_account_id, created_at, updated_at
                        )
                        VALUES (:planId, :groupId, 'Valid outing', 'COLLABORATING', :ownerId, :now, :now)
                        """)
                .param("planId", PLAN_ID)
                .param("groupId", GROUP_ID)
                .param("ownerId", OWNER_ID)
                .param("now", now)
                .update();
    }

    private Plan plan(UUID planId, String title, OffsetDateTime timestamp) {
        return new Plan(planId, GROUP_ID, title, PlanState.COLLABORATING, OWNER_ID, 1, timestamp, timestamp);
    }

    private RequirementDraft draft(UUID planId) {
        return new RequirementDraft(
                planId,
                ActivityCategory.COURT,
                "Asia/Manila",
                "BGC",
                5,
                4,
                10,
                0L,
                250000L,
                "Indoor court preferred.",
                "{\"hasParking\":true,\"courtCount\":2}");
    }

    private CandidateWindow window(UUID candidateWindowId, int sortOrder, OffsetDateTime startsAt) {
        return window(candidateWindowId, PLAN_ID, sortOrder, startsAt);
    }

    private CandidateWindow window(UUID candidateWindowId, UUID planId, int sortOrder, OffsetDateTime startsAt) {
        return new CandidateWindow(candidateWindowId, planId, sortOrder, startsAt, startsAt.plusHours(2), null);
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

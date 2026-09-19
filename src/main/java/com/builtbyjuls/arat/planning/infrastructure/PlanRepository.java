package com.builtbyjuls.arat.planning.infrastructure;

import com.builtbyjuls.arat.planning.domain.ActivityCategory;
import com.builtbyjuls.arat.planning.domain.CandidateWindow;
import com.builtbyjuls.arat.planning.domain.Plan;
import com.builtbyjuls.arat.planning.domain.PlanState;
import com.builtbyjuls.arat.planning.domain.PrivatePlan;
import com.builtbyjuls.arat.planning.domain.RequirementDraft;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class PlanRepository {

    private final JdbcClient jdbcClient;

    public PlanRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Plan insertNew(
            UUID planId,
            UUID groupId,
            String title,
            UUID createdByAccountId,
            RequirementDraft requirementDraft,
            List<CandidateWindow> candidateWindows,
            List<String> mustHaves) {
        if (!planId.equals(requirementDraft.planId())) {
            throw new IllegalArgumentException("requirement draft must belong to the plan");
        }
        validateCandidateWindows(planId, candidateWindows);
        validateMustHaves(mustHaves);
        var plan = jdbcClient.sql("""
                        INSERT INTO planning_plan (
                            plan_id, group_id, title, state, created_by_account_id,
                            version, created_at, updated_at
                        )
                        VALUES (
                            :planId, :groupId, :title, 'COLLABORATING', :createdByAccountId,
                            1, statement_timestamp(), statement_timestamp()
                        )
                        RETURNING plan_id, group_id, title, state, created_by_account_id,
                                  version, created_at, updated_at
                        """)
                .param("planId", planId)
                .param("groupId", groupId)
                .param("title", title)
                .param("createdByAccountId", createdByAccountId)
                .query(this::mapPlan)
                .single();
        insertRequirementDraft(requirementDraft);
        insertCandidateWindows(planId, candidateWindows);
        replaceMustHaves(planId, mustHaves);
        return plan;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void insert(Plan plan, RequirementDraft requirementDraft, List<CandidateWindow> candidateWindows, List<String> mustHaves) {
        if (!plan.planId().equals(requirementDraft.planId())) {
            throw new IllegalArgumentException("requirement draft must belong to the plan");
        }
        validateCandidateWindows(plan.planId(), candidateWindows);
        validateMustHaves(mustHaves);
        jdbcClient.sql("""
                        INSERT INTO planning_plan (
                            plan_id, group_id, title, state, created_by_account_id,
                            version, created_at, updated_at
                        )
                        VALUES (
                            :planId, :groupId, :title, :state, :createdByAccountId,
                            :version, :createdAt, :updatedAt
                        )
                        """)
                .param("planId", plan.planId())
                .param("groupId", plan.groupId())
                .param("title", plan.title())
                .param("state", plan.state().name())
                .param("createdByAccountId", plan.createdByAccountId())
                .param("version", plan.version())
                .param("createdAt", plan.createdAt())
                .param("updatedAt", plan.updatedAt())
                .update();
        insertRequirementDraft(requirementDraft);
        insertCandidateWindows(plan.planId(), candidateWindows);
        replaceMustHaves(plan.planId(), mustHaves);
    }

    @Transactional(readOnly = true)
    public Optional<PrivatePlan> findPrivate(UUID planId, UUID groupId) {
        var plan = jdbcClient.sql("""
                        SELECT p.plan_id, p.group_id, p.title, p.state, p.created_by_account_id,
                               p.version, p.created_at, p.updated_at,
                               d.category, d.time_zone, d.area_code, d.radius_km,
                               d.minimum_headcount, d.maximum_headcount,
                               d.budget_minimum_minor_units, d.budget_maximum_minor_units,
                               d.provider_safe_notes, d.category_attributes::text AS category_attributes,
                               ARRAY(
                                   SELECT candidate_window_id
                                   FROM planning_candidate_window
                                   WHERE plan_id = p.plan_id
                                     AND retired_at IS NULL
                                   ORDER BY sort_order
                               ) AS candidate_window_ids,
                               ARRAY(
                                   SELECT sort_order
                                   FROM planning_candidate_window
                                   WHERE plan_id = p.plan_id
                                     AND retired_at IS NULL
                                   ORDER BY sort_order
                               ) AS candidate_window_orders,
                               ARRAY(
                                   SELECT starts_at
                                   FROM planning_candidate_window
                                   WHERE plan_id = p.plan_id
                                     AND retired_at IS NULL
                                   ORDER BY sort_order
                               ) AS candidate_window_starts,
                               ARRAY(
                                   SELECT ends_at
                                   FROM planning_candidate_window
                                   WHERE plan_id = p.plan_id
                                     AND retired_at IS NULL
                                   ORDER BY sort_order
                               ) AS candidate_window_ends,
                               ARRAY(
                                   SELECT must_have
                                   FROM planning_requirement_must_have
                                   WHERE plan_id = p.plan_id
                                   ORDER BY sort_order
                               ) AS must_haves
                        FROM planning_plan p
                        JOIN planning_requirement_draft d ON d.plan_id = p.plan_id
                        WHERE p.plan_id = :planId
                          AND p.group_id = :groupId
                        """)
                .param("planId", planId)
                .param("groupId", groupId)
                .query(this::mapPrivatePlan)
                .optional();
        return plan;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Plan lockPlan(UUID planId) {
        return jdbcClient.sql("""
                        SELECT plan_id, group_id, title, state, created_by_account_id,
                               version, created_at, updated_at
                        FROM planning_plan
                        WHERE plan_id = :planId
                        FOR UPDATE
                        """)
                .param("planId", planId)
                .query(this::mapPlan)
                .single();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<Plan> updateVersioned(UUID planId, long expectedVersion, String title) {
        return jdbcClient.sql("""
                        UPDATE planning_plan
                        SET title = :title,
                            version = version + 1,
                            updated_at = statement_timestamp()
                        WHERE plan_id = :planId
                          AND version = :expectedVersion
                        RETURNING plan_id, group_id, title, state, created_by_account_id,
                                  version, created_at, updated_at
                        """)
                .param("planId", planId)
                .param("expectedVersion", expectedVersion)
                .param("title", title)
                .query(this::mapPlan)
                .optional();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<Plan> replaceRequirementDraftVersioned(
            UUID planId, long expectedVersion, String title, RequirementDraft requirementDraft) {
        if (!planId.equals(requirementDraft.planId())) {
            throw new IllegalArgumentException("requirement draft must belong to the plan");
        }
        var updatedPlan = updateVersioned(planId, expectedVersion, title);
        if (updatedPlan.isEmpty()) {
            return Optional.empty();
        }
        var draftUpdated = jdbcClient.sql("""
                        UPDATE planning_requirement_draft
                        SET category = :category,
                            time_zone = :timeZone,
                            area_code = :areaCode,
                            radius_km = :radiusKm,
                            minimum_headcount = :minimumHeadcount,
                            maximum_headcount = :maximumHeadcount,
                            budget_currency = :budgetCurrency,
                            budget_minimum_minor_units = :budgetMinimumMinorUnits,
                            budget_maximum_minor_units = :budgetMaximumMinorUnits,
                            provider_safe_notes = :providerSafeNotes,
                            category_attributes = CAST(:categoryAttributes AS jsonb)
                        WHERE plan_id = :planId
                        """)
                .param("planId", planId)
                .param("category", requirementDraft.category().name())
                .param("timeZone", requirementDraft.timeZone())
                .param("areaCode", requirementDraft.areaCode())
                .param("radiusKm", requirementDraft.radiusKm())
                .param("minimumHeadcount", requirementDraft.minimumHeadcount())
                .param("maximumHeadcount", requirementDraft.maximumHeadcount())
                .param("budgetCurrency", requirementDraft.budgetMinimumMinorUnits() == null ? null : "PHP")
                .param("budgetMinimumMinorUnits", requirementDraft.budgetMinimumMinorUnits())
                .param("budgetMaximumMinorUnits", requirementDraft.budgetMaximumMinorUnits())
                .param("providerSafeNotes", requirementDraft.providerSafeNotes())
                .param("categoryAttributes", requirementDraft.categoryAttributes())
                .update();
        if (draftUpdated != 1) {
            throw new IllegalStateException("requirement draft disappeared under plan lock");
        }
        return updatedPlan;
    }

    @Transactional(readOnly = true)
    public List<Plan> listPage(UUID groupId, int limit) {
        return jdbcClient.sql("""
                        SELECT plan_id, group_id, title, state, created_by_account_id,
                               version, created_at, updated_at
                        FROM planning_plan
                        WHERE group_id = :groupId
                        ORDER BY created_at DESC, plan_id DESC
                        LIMIT :limit
                        """)
                .param("groupId", groupId)
                .param("limit", limit)
                .query(this::mapPlan)
                .list();
    }

    @Transactional(readOnly = true)
    public List<Plan> listPage(UUID groupId, OffsetDateTime createdAt, UUID planId, int limit) {
        return jdbcClient.sql("""
                        SELECT plan_id, group_id, title, state, created_by_account_id,
                               version, created_at, updated_at
                        FROM planning_plan
                        WHERE group_id = :groupId
                          AND (created_at, plan_id) < (:createdAt, :planId)
                        ORDER BY created_at DESC, plan_id DESC
                        LIMIT :limit
                        """)
                .param("groupId", groupId)
                .param("createdAt", createdAt)
                .param("planId", planId)
                .param("limit", limit)
                .query(this::mapPlan)
                .list();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void reconcileCandidateWindows(UUID planId, List<CandidateWindow> candidateWindows) {
        validateCandidateWindows(planId, candidateWindows);
        Set<UUID> retainedIds = new HashSet<>();
        for (var candidateWindow : candidateWindows) {
            if (!planId.equals(candidateWindow.planId())) {
                throw new IllegalArgumentException("candidate window must belong to the plan");
            }
            retainedIds.add(candidateWindow.candidateWindowId());
        }
        var existing = findCandidateWindowsIncludingRetired(planId);
        var existingIds = existing.stream()
                .filter(candidateWindow -> candidateWindow.retiredAt() == null)
                .map(CandidateWindow::candidateWindowId)
                .collect(java.util.stream.Collectors.toSet());
        var retiredIds = existing.stream()
                .filter(candidateWindow -> candidateWindow.retiredAt() != null)
                .map(CandidateWindow::candidateWindowId)
                .collect(java.util.stream.Collectors.toSet());
        if (retainedIds.stream().anyMatch(retiredIds::contains)) {
            throw new IllegalArgumentException("retired candidate windows cannot be restored");
        }
        jdbcClient.sql("""
                        UPDATE planning_candidate_window
                        SET retired_at = statement_timestamp()
                        WHERE plan_id = :planId
                          AND retired_at IS NULL
                          AND candidate_window_id <> ALL(:retainedIds)
                        """)
                .param("planId", planId)
                .param("retainedIds", retainedIds.toArray(UUID[]::new))
                .update();
        jdbcClient.sql("""
                        UPDATE planning_candidate_window
                        SET sort_order = sort_order + 10000
                        WHERE plan_id = :planId
                          AND retired_at IS NULL
                          AND candidate_window_id = ANY(:retainedIds)
                        """)
                .param("planId", planId)
                .param("retainedIds", retainedIds.toArray(UUID[]::new))
                .update();
        for (var candidateWindow : candidateWindows) {
            if (existingIds.contains(candidateWindow.candidateWindowId())) {
                jdbcClient.sql("""
                                UPDATE planning_candidate_window
                                SET sort_order = :sortOrder,
                                    starts_at = :startsAt,
                                    ends_at = :endsAt,
                                    retired_at = NULL
                                WHERE candidate_window_id = :candidateWindowId
                                  AND plan_id = :planId
                                """)
                        .param("candidateWindowId", candidateWindow.candidateWindowId())
                        .param("planId", planId)
                        .param("sortOrder", candidateWindow.sortOrder())
                        .param("startsAt", candidateWindow.startsAt())
                        .param("endsAt", candidateWindow.endsAt())
                        .update();
            } else {
                insertCandidateWindow(candidateWindow);
            }
        }
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void replaceMustHaves(UUID planId, List<String> mustHaves) {
        validateMustHaves(mustHaves);
        jdbcClient.sql("DELETE FROM planning_requirement_must_have WHERE plan_id = :planId")
                .param("planId", planId)
                .update();
        for (var index = 0; index < mustHaves.size(); index++) {
            jdbcClient.sql("""
                            INSERT INTO planning_requirement_must_have (plan_id, must_have, sort_order)
                            VALUES (:planId, :mustHave, :sortOrder)
                            """)
                    .param("planId", planId)
                    .param("mustHave", mustHaves.get(index))
                    .param("sortOrder", index + 1)
                    .update();
        }
    }

    private void insertRequirementDraft(RequirementDraft requirementDraft) {
        jdbcClient.sql("""
                        INSERT INTO planning_requirement_draft (
                            plan_id, category, time_zone, area_code, radius_km,
                            minimum_headcount, maximum_headcount, budget_currency,
                            budget_minimum_minor_units, budget_maximum_minor_units,
                            provider_safe_notes, category_attributes
                        )
                        VALUES (
                            :planId, :category, :timeZone, :areaCode, :radiusKm,
                            :minimumHeadcount, :maximumHeadcount, :budgetCurrency,
                            :budgetMinimumMinorUnits, :budgetMaximumMinorUnits,
                            :providerSafeNotes, CAST(:categoryAttributes AS jsonb)
                        )
                        """)
                .param("planId", requirementDraft.planId())
                .param("category", requirementDraft.category().name())
                .param("timeZone", requirementDraft.timeZone())
                .param("areaCode", requirementDraft.areaCode())
                .param("radiusKm", requirementDraft.radiusKm())
                .param("minimumHeadcount", requirementDraft.minimumHeadcount())
                .param("maximumHeadcount", requirementDraft.maximumHeadcount())
                .param("budgetCurrency", requirementDraft.budgetMinimumMinorUnits() == null ? null : "PHP")
                .param("budgetMinimumMinorUnits", requirementDraft.budgetMinimumMinorUnits())
                .param("budgetMaximumMinorUnits", requirementDraft.budgetMaximumMinorUnits())
                .param("providerSafeNotes", requirementDraft.providerSafeNotes())
                .param("categoryAttributes", requirementDraft.categoryAttributes())
                .update();
    }

    private void validateCandidateWindows(UUID planId, List<CandidateWindow> candidateWindows) {
        if (candidateWindows.size() < 1 || candidateWindows.size() > 10) {
            throw new IllegalArgumentException("candidateWindows must contain between 1 and 10 windows");
        }
        var sortOrders = new HashSet<Integer>();
        var ids = new HashSet<UUID>();
        for (var candidateWindow : candidateWindows) {
            if (!planId.equals(candidateWindow.planId())) {
                throw new IllegalArgumentException("candidate window must belong to the plan");
            }
            if (!sortOrders.add(candidateWindow.sortOrder()) || !ids.add(candidateWindow.candidateWindowId())) {
                throw new IllegalArgumentException("candidate windows must have unique IDs and sort orders");
            }
        }
    }

    private void validateMustHaves(List<String> mustHaves) {
        if (mustHaves.size() > 20 || new HashSet<>(mustHaves).size() != mustHaves.size()) {
            throw new IllegalArgumentException("mustHaves must contain at most 20 unique values");
        }
    }

    private void insertCandidateWindows(UUID planId, List<CandidateWindow> candidateWindows) {
        for (var candidateWindow : candidateWindows) {
            if (!planId.equals(candidateWindow.planId())) {
                throw new IllegalArgumentException("candidate window must belong to the plan");
            }
            insertCandidateWindow(candidateWindow);
        }
    }

    private void insertCandidateWindow(CandidateWindow candidateWindow) {
        jdbcClient.sql("""
                        INSERT INTO planning_candidate_window (
                            candidate_window_id, plan_id, sort_order, starts_at, ends_at, retired_at
                        )
                        VALUES (:candidateWindowId, :planId, :sortOrder, :startsAt, :endsAt, :retiredAt)
                        """)
                .param("candidateWindowId", candidateWindow.candidateWindowId())
                .param("planId", candidateWindow.planId())
                .param("sortOrder", candidateWindow.sortOrder())
                .param("startsAt", candidateWindow.startsAt())
                .param("endsAt", candidateWindow.endsAt())
                .param("retiredAt", candidateWindow.retiredAt())
                .update();
    }

    private List<CandidateWindow> findCandidateWindowsIncludingRetired(UUID planId) {
        return jdbcClient.sql("""
                        SELECT candidate_window_id, plan_id, sort_order, starts_at, ends_at, retired_at
                        FROM planning_candidate_window
                        WHERE plan_id = :planId
                        """)
                .param("planId", planId)
                .query(this::mapCandidateWindow)
                .list();
    }

    private PrivatePlan mapPrivatePlan(ResultSet resultSet, int rowNum) throws SQLException {
        var plan = mapPlan(resultSet, rowNum);
        var candidateWindowIds = (Object[]) resultSet.getArray("candidate_window_ids").getArray();
        var candidateWindowOrders = (Object[]) resultSet.getArray("candidate_window_orders").getArray();
        var candidateWindowStarts = (Object[]) resultSet.getArray("candidate_window_starts").getArray();
        var candidateWindowEnds = (Object[]) resultSet.getArray("candidate_window_ends").getArray();
        var candidateWindows = new java.util.ArrayList<CandidateWindow>();
        for (var index = 0; index < candidateWindowIds.length; index++) {
            candidateWindows.add(new CandidateWindow(
                    (UUID) candidateWindowIds[index],
                    plan.planId(),
                    (Integer) candidateWindowOrders[index],
                    ((java.sql.Timestamp) candidateWindowStarts[index]).toInstant().atOffset(java.time.ZoneOffset.UTC),
                    ((java.sql.Timestamp) candidateWindowEnds[index]).toInstant().atOffset(java.time.ZoneOffset.UTC),
                    null));
        }
        var mustHaves = (Object[]) resultSet.getArray("must_haves").getArray();
        return new PrivatePlan(
                plan,
                mapRequirementDraft(resultSet, rowNum),
                candidateWindows,
                java.util.Arrays.stream(mustHaves).map(String.class::cast).toList());
    }

    private Plan mapPlan(ResultSet resultSet, int rowNum) throws SQLException {
        return new Plan(
                resultSet.getObject("plan_id", UUID.class),
                resultSet.getObject("group_id", UUID.class),
                resultSet.getString("title"),
                PlanState.valueOf(resultSet.getString("state")),
                resultSet.getObject("created_by_account_id", UUID.class),
                resultSet.getLong("version"),
                resultSet.getObject("created_at", OffsetDateTime.class),
                resultSet.getObject("updated_at", OffsetDateTime.class));
    }

    private RequirementDraft mapRequirementDraft(ResultSet resultSet, int rowNum) throws SQLException {
        return new RequirementDraft(
                resultSet.getObject("plan_id", UUID.class),
                ActivityCategory.valueOf(resultSet.getString("category")),
                resultSet.getString("time_zone"),
                resultSet.getString("area_code"),
                resultSet.getInt("radius_km"),
                resultSet.getInt("minimum_headcount"),
                resultSet.getInt("maximum_headcount"),
                resultSet.getObject("budget_minimum_minor_units", Long.class),
                resultSet.getObject("budget_maximum_minor_units", Long.class),
                resultSet.getString("provider_safe_notes"),
                resultSet.getString("category_attributes"));
    }

    private CandidateWindow mapCandidateWindow(ResultSet resultSet, int rowNum) throws SQLException {
        return new CandidateWindow(
                resultSet.getObject("candidate_window_id", UUID.class),
                resultSet.getObject("plan_id", UUID.class),
                resultSet.getInt("sort_order"),
                resultSet.getObject("starts_at", OffsetDateTime.class),
                resultSet.getObject("ends_at", OffsetDateTime.class),
                resultSet.getObject("retired_at", OffsetDateTime.class));
    }

}

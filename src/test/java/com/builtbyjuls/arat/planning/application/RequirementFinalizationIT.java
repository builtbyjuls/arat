package com.builtbyjuls.arat.planning.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.builtbyjuls.arat.PostgreSqlIntegrationTest;
import com.builtbyjuls.arat.identity.api.AuthenticatedActor;
import com.builtbyjuls.arat.planning.api.FinalizeRequirementsCommand;
import com.builtbyjuls.arat.planning.api.FinalizeRequirementsRequest;
import com.builtbyjuls.arat.web.CorrelationIdFilter;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

@SpringBootTest
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class RequirementFinalizationIT extends PostgreSqlIntegrationTest {
    private static final UUID ORGANIZER_ID = UUID.fromString("88000000-0000-4000-8000-000000000001");
    private static final UUID MEMBER_ID = UUID.fromString("88000000-0000-4000-8000-000000000002");
    private static final UUID OUTSIDER_ID = UUID.fromString("88000000-0000-4000-8000-000000000003");
    private static final UUID GROUP_ID = UUID.fromString("88000000-0000-4000-8000-000000000010");
    private static final UUID PLAN_ID = UUID.fromString("88000000-0000-4000-8000-000000000011");
    private static final UUID WINDOW_ID = UUID.fromString("88000000-0000-4000-8000-000000000012");

    @Autowired private WebApplicationContext context;
    @Autowired private CorrelationIdFilter correlationIdFilter;
    @Autowired private JdbcClient jdbcClient;
    @Autowired private DataSource dataSource;
    @Autowired private RequirementFinalizationService service;
    @Autowired private RequirementReplacementService requirementReplacementService;
    private MockMvc mockMvc;

    @BeforeEach
    void prepareDatabase() {
        removeFailureTrigger();
        jdbcClient.sql("DELETE FROM audit_event").update();
        jdbcClient.sql("DELETE FROM idempotency_record").update();
        jdbcClient.sql("TRUNCATE TABLE planning_requirement_finalization").update();
        jdbcClient.sql("DELETE FROM planning_plan_preference").update();
        jdbcClient.sql("DELETE FROM planning_requirement_must_have").update();
        jdbcClient.sql("DELETE FROM planning_candidate_window").update();
        jdbcClient.sql("DELETE FROM planning_requirement_draft").update();
        jdbcClient.sql("DELETE FROM planning_plan").update();
        jdbcClient.sql("DELETE FROM group_membership").update();
        jdbcClient.sql("DELETE FROM group_account").update();
        jdbcClient.sql("DELETE FROM identity_account WHERE account_id IN (:organizer, :member, :outsider)")
                .param("organizer", ORGANIZER_ID).param("member", MEMBER_ID).param("outsider", OUTSIDER_ID).update();
        jdbcClient.sql("INSERT INTO identity_account (account_id, display_name) VALUES (:organizer, 'Final organizer'), (:member, 'Final member'), (:outsider, 'Final outsider')")
                .param("organizer", ORGANIZER_ID).param("member", MEMBER_ID).param("outsider", OUTSIDER_ID).update();
        jdbcClient.sql("INSERT INTO group_account (group_id, name, description, status, created_by_account_id) VALUES (:groupId, 'Final group', '', 'ACTIVE', :organizer)")
                .param("groupId", GROUP_ID).param("organizer", ORGANIZER_ID).update();
        jdbcClient.sql("INSERT INTO group_membership (group_id, account_id, role, status, joined_at) VALUES (:groupId, :organizer, 'ORGANIZER', 'ACTIVE', statement_timestamp()), (:groupId, :member, 'MEMBER', 'ACTIVE', statement_timestamp())")
                .param("groupId", GROUP_ID).param("organizer", ORGANIZER_ID).param("member", MEMBER_ID).update();
        jdbcClient.sql("INSERT INTO planning_plan (plan_id, group_id, title, state, created_by_account_id, version) VALUES (:planId, :groupId, 'Final plan', 'COLLABORATING', :organizer, 1)")
                .param("planId", PLAN_ID).param("groupId", GROUP_ID).param("organizer", ORGANIZER_ID).update();
        jdbcClient.sql("INSERT INTO planning_requirement_draft (plan_id, category, time_zone, area_code, radius_km, minimum_headcount, maximum_headcount, category_attributes) VALUES (:planId, 'COURT', 'Asia/Manila', 'BGC', 5, 4, 10, '{\"accessTokenMode\":true}'::jsonb)")
                .param("planId", PLAN_ID).update();
        jdbcClient.sql("INSERT INTO planning_candidate_window (candidate_window_id, plan_id, sort_order, starts_at, ends_at) VALUES (:windowId, :planId, 1, clock_timestamp() + interval '2 days', clock_timestamp() + interval '2 days 2 hours')")
                .param("windowId", WINDOW_ID).param("planId", PLAN_ID).update();
        mockMvc = MockMvcBuilders.webAppContextSetup(context).addFilters(correlationIdFilter).apply(springSecurity()).build();
    }

    @AfterEach
    void removeFailureTrigger() {
        jdbcClient.sql("DROP TRIGGER IF EXISTS fail_finalization_audit ON audit_event").update();
        jdbcClient.sql("DROP FUNCTION IF EXISTS test_fail_finalization_audit()").update();
    }

    @Test
    void finalizesImmutableSnapshotAndExactlyReplaysAfterLaterDraftEdit() throws Exception {
        var multibyte = "\u4e00".repeat(120);
        jdbcClient.sql("UPDATE planning_requirement_draft SET provider_safe_notes = :notes, category_attributes = jsonb_build_object('tokenValue', :value, 'secretValue', :value, 'authorizationValue', :value, 'passwordValue', :value, 'cookieValue', :value) || (SELECT jsonb_object_agg('attribute' || n, :value) FROM generate_series(1, 15) AS n) WHERE plan_id = :planId")
                .param("planId", PLAN_ID).param("notes", "\u4e00".repeat(1000)).param("value", multibyte).update();
        for (var index = 1; index <= 20; index++) {
            jdbcClient.sql("INSERT INTO planning_requirement_must_have (plan_id, must_have, sort_order) VALUES (:planId, :value, :order)")
                    .param("planId", PLAN_ID).param("value", "\u4e00".repeat(118) + String.format("%02d", index)).param("order", index).update();
        }
        var first = finalizeAs(ORGANIZER_ID, "final-key", WINDOW_ID, deadline(), "\"1\"")
                .andExpect(status().isCreated()).andExpect(header().string("ETag", "\"1\""))
                .andExpect(jsonPath("$.basisPlanVersion").value(1))
                .andExpect(jsonPath("$.categoryAttributes.attribute1").exists())
                .andReturn().getResponse();
        var start = jdbcClient.sql("SELECT starts_at FROM planning_candidate_window WHERE candidate_window_id = :windowId").param("windowId", WINDOW_ID).query(OffsetDateTime.class).single();
        requirementReplacementService.replace(new com.builtbyjuls.arat.planning.api.ReplaceRequirementsCommand(
                ORGANIZER_ID, PLAN_ID, 1,
                new com.builtbyjuls.arat.planning.api.RequirementReplacementRequest(
                        "Replaced after finalization", com.builtbyjuls.arat.planning.domain.ActivityCategory.COURT,
                        "Asia/Manila", List.of(new com.builtbyjuls.arat.planning.api.RequirementReplacementRequest.CandidateWindowRequest(WINDOW_ID, start, start.plusHours(2))),
                        new com.builtbyjuls.arat.planning.api.CreatePlanRequest.AreaRequest("MAKATI", 5),
                        new com.builtbyjuls.arat.planning.api.CreatePlanRequest.HeadcountRequest(4, 10), null,
                        List.of(), null, Map.of()), "replacement-after-finalization"));
        var replay = finalizeAs(ORGANIZER_ID, "final-key", WINDOW_ID, deadline(), "\"1\"")
                .andExpect(status().isCreated()).andExpect(header().string("ETag", "\"1\""))
                .andExpect(header().string("Location", first.getHeader("Location")))
                .andReturn().getResponse();
        assertThat(replay.getContentAsString()).isEqualTo(first.getContentAsString());
        assertThat(count("SELECT count(*) FROM planning_requirement_finalization")).isOne();
        assertThat(count("SELECT count(*) FROM idempotency_record WHERE operation = 'plans.requirements.finalize' AND state = 'COMPLETED'")).isOne();
        assertThat(jdbcClient.sql("SELECT replay_state::text FROM idempotency_record WHERE operation = 'plans.requirements.finalize'").query(String.class).single())
                .doesNotContain("attribute1", "attribute2");
        assertThat(first.getContentAsByteArray()).hasSizeGreaterThan(16_384);
        assertThat(planVersion()).isEqualTo(2);
    }

    @Test
    void rejectsAuthorityPreconditionsWindowsAndDeadlinesWithoutWrites() throws Exception {
        finalizeAs(MEMBER_ID, "member", WINDOW_ID, deadline(), "\"1\"").andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN_ROLE"));
        finalizeAs(OUTSIDER_ID, "outsider", WINDOW_ID, deadline(), "\"1\"").andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("PRIVATE_RESOURCE_NOT_FOUND"));
        finalizeAs(ORGANIZER_ID, "missing", WINDOW_ID, deadline(), null).andExpect(status().isPreconditionRequired());
        finalizeAs(ORGANIZER_ID, "stale", WINDOW_ID, deadline(), "\"2\"").andExpect(status().isPreconditionFailed());
        finalizeAs(ORGANIZER_ID, "window", UUID.randomUUID(), deadline(), "\"1\"").andExpect(status().isUnprocessableEntity());
        var otherPlanId = UUID.randomUUID();
        var crossPlanWindowId = UUID.randomUUID();
        jdbcClient.sql("INSERT INTO planning_plan (plan_id, group_id, title, state, created_by_account_id, version) VALUES (:planId, :groupId, 'Other plan', 'COLLABORATING', :organizer, 1)")
                .param("planId", otherPlanId).param("groupId", GROUP_ID).param("organizer", ORGANIZER_ID).update();
        jdbcClient.sql("INSERT INTO planning_candidate_window (candidate_window_id, plan_id, sort_order, starts_at, ends_at) VALUES (:windowId, :planId, 1, clock_timestamp() + interval '3 days', clock_timestamp() + interval '3 days 2 hours')")
                .param("windowId", crossPlanWindowId).param("planId", otherPlanId).update();
        finalizeAs(ORGANIZER_ID, "cross-plan-window", crossPlanWindowId, deadline(), "\"1\"").andExpect(status().isUnprocessableEntity());
        finalizeAs(ORGANIZER_ID, "past", WINDOW_ID, OffsetDateTime.now().minusDays(1), "\"1\"").andExpect(status().isUnprocessableEntity());
        var start = jdbcClient.sql("SELECT starts_at FROM planning_candidate_window WHERE candidate_window_id = :windowId").param("windowId", WINDOW_ID).query(OffsetDateTime.class).single();
        finalizeAs(ORGANIZER_ID, "equal", WINDOW_ID, start, "\"1\"").andExpect(status().isUnprocessableEntity());
        finalizeAs(ORGANIZER_ID, "after", WINDOW_ID, start.plusSeconds(1), "\"1\"").andExpect(status().isUnprocessableEntity());
        jdbcClient.sql("UPDATE planning_candidate_window SET retired_at = clock_timestamp() WHERE candidate_window_id = :windowId").param("windowId", WINDOW_ID).update();
        finalizeAs(ORGANIZER_ID, "retired", WINDOW_ID, start.minusHours(1), "\"1\"").andExpect(status().isUnprocessableEntity());
        assertThat(count("SELECT count(*) FROM planning_requirement_finalization")).isZero();
        assertThat(count("SELECT count(*) FROM audit_event")).isZero();
    }

    @Test
    void rejectsDatabasePrecisionAndStateAndReportsPreferenceWarnings() throws Exception {
        jdbcClient.sql("INSERT INTO planning_plan_preference (plan_id, account_id, basis_plan_version, attendance, guest_count, version) VALUES (:planId, :member, 1, 'JOINING', 0, 1)")
                .param("planId", PLAN_ID).param("member", MEMBER_ID).update();
        finalizeAs(ORGANIZER_ID, "counts", WINDOW_ID, deadline(), "\"1\"")
                .andExpect(status().isCreated()).andExpect(jsonPath("$.currentPreferenceCount").value(1))
                .andExpect(jsonPath("$.stalePreferenceCount").value(0)).andExpect(jsonPath("$.warnings").isEmpty());
        jdbcClient.sql("TRUNCATE TABLE planning_requirement_finalization").update();
        jdbcClient.sql("DELETE FROM idempotency_record").update();
        finalizeAs(ORGANIZER_ID, "precision", WINDOW_ID, deadline().plusNanos(999), "\"1\"")
                .andExpect(status().isUnprocessableEntity());
        jdbcClient.sql("UPDATE planning_plan SET state = 'CANCELLED' WHERE plan_id = :planId").param("planId", PLAN_ID).update();
        finalizeAs(ORGANIZER_ID, "state", WINDOW_ID, deadline(), "\"1\"")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("INVALID_PLAN_STATE"));
    }

    @Test
    void rejectsChangedPayloadUnderUsedKeyWithoutASecondFinalization() throws Exception {
        finalizeAs(ORGANIZER_ID, "reused", WINDOW_ID, deadline(), "\"1\"").andExpect(status().isCreated());
        finalizeAs(ORGANIZER_ID, "reused", UUID.randomUUID(), deadline(), "\"1\"")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
        assertThat(count("SELECT count(*) FROM planning_requirement_finalization")).isOne();
    }

    @Test
    void reportsStalePreferenceWarning() throws Exception {
        jdbcClient.sql("INSERT INTO planning_plan_preference (plan_id, account_id, basis_plan_version, attendance, guest_count, version) VALUES (:planId, :member, 1, 'JOINING', 0, 1)")
                .param("planId", PLAN_ID).param("member", MEMBER_ID).update();
        jdbcClient.sql("UPDATE planning_plan SET version = 2 WHERE plan_id = :planId").param("planId", PLAN_ID).update();
        finalizeAs(ORGANIZER_ID, "stale-warning", WINDOW_ID, deadline(), "\"2\"")
                .andExpect(status().isCreated()).andExpect(jsonPath("$.currentPreferenceCount").value(0))
                .andExpect(jsonPath("$.stalePreferenceCount").value(1))
                .andExpect(jsonPath("$.warnings[0]").value("NO_CURRENT_PREFERENCE_INPUT"))
                .andExpect(jsonPath("$.warnings[1]").value("STALE_PREFERENCE_INPUT_PRESENT"));
    }

    @Test
    void concurrentSameKeyRetriesCreateOneFinalization() throws Exception {
        var barrier = new CyclicBarrier(2);
        try (var blocker = dataSource.getConnection()) {
            blocker.setAutoCommit(false);
            try (var statement = blocker.prepareStatement("SELECT group_id FROM group_account WHERE group_id = ? FOR UPDATE")) {
                statement.setObject(1, GROUP_ID);
                statement.executeQuery();
            }
            try (var executor = Executors.newFixedThreadPool(2)) {
                var first = executor.submit(() -> finalizeAfterBarrier(barrier));
                var second = executor.submit(() -> finalizeAfterBarrier(barrier));
                try {
                    awaitBlockedTransactions(2);
                } finally {
                    blocker.commit();
                }
                assertThat(first.get(10, TimeUnit.SECONDS).finalizationId()).isEqualTo(second.get(10, TimeUnit.SECONDS).finalizationId());
            }
        }
        assertThat(count("SELECT count(*) FROM planning_requirement_finalization")).isOne();
        assertThat(count("SELECT count(*) FROM audit_event WHERE action = 'plan.requirements.finalized'")).isOne();
    }

    @Test
    void rollsBackFinalizationAndIdempotencyWhenAuditFails() throws Exception {
        jdbcClient.sql("CREATE FUNCTION test_fail_finalization_audit() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'forced finalization audit failure'; END; $$").update();
        jdbcClient.sql("CREATE TRIGGER fail_finalization_audit BEFORE INSERT ON audit_event FOR EACH ROW WHEN (NEW.action = 'plan.requirements.finalized') EXECUTE FUNCTION test_fail_finalization_audit()").update();
        finalizeAs(ORGANIZER_ID, "rollback", WINDOW_ID, deadline(), "\"1\"").andExpect(status().isInternalServerError());
        assertThat(count("SELECT count(*) FROM planning_requirement_finalization")).isZero();
        assertThat(count("SELECT count(*) FROM idempotency_record")).isZero();
    }

    @Test
    void publishesExecutableOpenApi() throws Exception {
        mockMvc.perform(get("/v3/api-docs")).andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/v1/plans/{planId}/requirement-finalization'].post.operationId").value("finalizePlanRequirements"))
                .andExpect(jsonPath("$.paths['/api/v1/plans/{planId}/requirement-finalization'].post.parameters[?(@.name == 'Idempotency-Key')].required").value(true));
    }

    private org.springframework.test.web.servlet.ResultActions finalizeAs(UUID actor, String key, UUID windowId, OffsetDateTime deadline, String etag) throws Exception {
        var request = post("/api/v1/plans/{planId}/requirement-finalization", PLAN_ID).with(actor(actor)).header("Idempotency-Key", key).contentType("application/json").content("{\"candidateWindowId\":\"" + windowId + "\",\"offerDeadline\":\"" + deadline + "\"}");
        if (etag != null) request.header("If-Match", etag);
        return mockMvc.perform(request);
    }
    private com.builtbyjuls.arat.planning.api.RequirementFinalizationRepresentation finalizeAfterBarrier(CyclicBarrier barrier) throws Exception {
        barrier.await(10, TimeUnit.SECONDS);
        return service.finalizeRequirements(new FinalizeRequirementsCommand(ORGANIZER_ID, PLAN_ID, 1, new FinalizeRequirementsRequest(WINDOW_ID, deadline()), "concurrent-key", "concurrent"));
    }
    private void awaitBlockedTransactions(int expected) {
        var expiresAt = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < expiresAt) {
            var blocked = jdbcClient.sql("SELECT count(*) FROM pg_stat_activity WHERE datname = current_database() AND cardinality(pg_blocking_pids(pid)) > 0")
                    .query(Integer.class).single();
            if (blocked >= expected) return;
            Thread.onSpinWait();
        }
        throw new AssertionError("Timed out waiting for " + expected + " blocked transactions");
    }
    private OffsetDateTime deadline() { return jdbcClient.sql("SELECT starts_at - interval '1 hour' FROM planning_candidate_window WHERE candidate_window_id = :windowId").param("windowId", WINDOW_ID).query(OffsetDateTime.class).single(); }
    private RequestPostProcessor actor(UUID id) { return SecurityMockMvcRequestPostProcessors.authentication(new TestingAuthenticationToken(new AuthenticatedActor(id, Set.of()), null, "ROLE_USER")); }
    private long count(String sql) { return jdbcClient.sql(sql).query(Long.class).single(); }
    private long planVersion() { return jdbcClient.sql("SELECT version FROM planning_plan WHERE plan_id = :planId").param("planId", PLAN_ID).query(Long.class).single(); }
}

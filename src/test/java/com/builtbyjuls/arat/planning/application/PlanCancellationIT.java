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
import com.builtbyjuls.arat.marketplace.application.PlanCancellationService;
import com.builtbyjuls.arat.planning.api.CancelPlanCommand;
import com.builtbyjuls.arat.planning.api.CreatePreferenceCommand;
import com.builtbyjuls.arat.planning.api.CreatePreferenceRequest;
import com.builtbyjuls.arat.planning.api.PlanCancellationException;
import com.builtbyjuls.arat.planning.api.PlanRepresentation;
import com.builtbyjuls.arat.planning.api.ReplaceRequirementsCommand;
import com.builtbyjuls.arat.planning.api.RequirementReplacementException;
import com.builtbyjuls.arat.planning.api.RequirementReplacementRequest;
import com.builtbyjuls.arat.planning.domain.ActivityCategory;
import com.builtbyjuls.arat.planning.domain.Attendance;
import com.builtbyjuls.arat.planning.domain.PlanState;
import com.builtbyjuls.arat.web.CorrelationIdFilter;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
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
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PlanCancellationIT extends PostgreSqlIntegrationTest {

    private static final UUID ORGANIZER_ID = UUID.fromString("79000000-0000-4000-8000-000000000001");
    private static final UUID MEMBER_ID = UUID.fromString("79000000-0000-4000-8000-000000000002");
    private static final UUID OUTSIDER_ID = UUID.fromString("79000000-0000-4000-8000-000000000003");
    private static final UUID GROUP_ID = UUID.fromString("7a000000-0000-4000-8000-000000000001");
    private static final UUID PLAN_ID = UUID.fromString("7b000000-0000-4000-8000-000000000001");
    private static final UUID WINDOW_ID = UUID.fromString("7c000000-0000-4000-8000-000000000001");
    private static final UUID REQUEST_ID = UUID.fromString("7d000000-0000-4000-8000-000000000001");
    private static final UUID FIRST_PROVIDER_ID = UUID.fromString("7e000000-0000-4000-8000-000000000001");
    private static final UUID SECOND_PROVIDER_ID = UUID.fromString("7e000000-0000-4000-8000-000000000002");

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private CorrelationIdFilter correlationIdFilter;

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private PlanCancellationService planCancellationService;

    @Autowired
    private RequirementReplacementService requirementReplacementService;

    @Autowired
    private PlanPreferenceService planPreferenceService;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private MockMvc mockMvc;

    @BeforeEach
    void prepareDatabase() {
        removeAuditFailureTrigger();
        jdbcClient.sql("DELETE FROM messaging_outbox").update();
        jdbcClient.sql("DELETE FROM audit_event").update();
        jdbcClient.sql("DELETE FROM idempotency_record").update();
        jdbcClient.sql("TRUNCATE TABLE marketplace_request_recipient, planning_published_request CASCADE").update();
        jdbcClient.sql("DELETE FROM planning_preference_ranked_item").update();
        jdbcClient.sql("DELETE FROM planning_preference_selected_window").update();
        jdbcClient.sql("DELETE FROM planning_plan_preference").update();
        jdbcClient.sql("DELETE FROM planning_requirement_must_have").update();
        jdbcClient.sql("DELETE FROM planning_candidate_window").update();
        jdbcClient.sql("DELETE FROM planning_requirement_draft").update();
        jdbcClient.sql("DELETE FROM planning_plan").update();
        jdbcClient.sql("DELETE FROM provider_organization WHERE provider_id IN (:firstProviderId, :secondProviderId)")
                .param("firstProviderId", FIRST_PROVIDER_ID)
                .param("secondProviderId", SECOND_PROVIDER_ID)
                .update();
        jdbcClient.sql("DELETE FROM group_membership").update();
        jdbcClient.sql("DELETE FROM group_account").update();
        jdbcClient.sql("DELETE FROM identity_account WHERE account_id IN (:organizerId, :memberId, :outsiderId)")
                .param("organizerId", ORGANIZER_ID)
                .param("memberId", MEMBER_ID)
                .param("outsiderId", OUTSIDER_ID)
                .update();
        jdbcClient.sql("""
                        INSERT INTO identity_account (account_id, display_name) VALUES
                            (:organizerId, 'Cancellation organizer'),
                            (:memberId, 'Cancellation member'),
                            (:outsiderId, 'Cancellation outsider')
                        """)
                .param("organizerId", ORGANIZER_ID)
                .param("memberId", MEMBER_ID)
                .param("outsiderId", OUTSIDER_ID)
                .update();
        jdbcClient.sql("""
                        INSERT INTO group_account (group_id, name, description, status, created_by_account_id)
                        VALUES (:groupId, 'Cancellation group', '', 'ACTIVE', :organizerId)
                        """)
                .param("groupId", GROUP_ID)
                .param("organizerId", ORGANIZER_ID)
                .update();
        jdbcClient.sql("""
                        INSERT INTO group_membership (group_id, account_id, role, status, joined_at) VALUES
                            (:groupId, :organizerId, 'ORGANIZER', 'ACTIVE', statement_timestamp()),
                            (:groupId, :memberId, 'MEMBER', 'ACTIVE', statement_timestamp())
                        """)
                .param("groupId", GROUP_ID)
                .param("organizerId", ORGANIZER_ID)
                .param("memberId", MEMBER_ID)
                .update();
        insertPlan();
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .addFilters(correlationIdFilter)
                .apply(springSecurity())
                .build();
    }

    @AfterEach
    void removeAuditFailureTrigger() {
        jdbcClient.sql("DROP TRIGGER IF EXISTS fail_plan_cancelled_audit ON audit_event").update();
        jdbcClient.sql("DROP FUNCTION IF EXISTS test_fail_plan_cancelled_audit()").update();
    }

    @Test
    void organizerCancelsAtomicallyAndAuthorizedReadsPreserveDraftAndPreferences() throws Exception {
        insertPreference();

        cancelAs(ORGANIZER_ID, "cancel-success", "\"1\"")
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"2\""))
                .andExpect(jsonPath("$.planId").value(PLAN_ID.toString()))
                .andExpect(jsonPath("$.state").value("CANCELLED"))
                .andExpect(jsonPath("$.version").value(2));

        assertThat(planState()).isEqualTo(PlanState.CANCELLED);
        assertThat(planVersion()).isEqualTo(2);
        assertThat(count("SELECT count(*) FROM planning_requirement_draft WHERE plan_id = :planId")).isOne();
        assertThat(count("SELECT count(*) FROM planning_plan_preference WHERE plan_id = :planId")).isOne();
        assertThat(count("SELECT count(*) FROM audit_event WHERE action = 'plan.cancelled' AND plan_id = :planId")).isOne();
        assertThat(count("SELECT count(*) FROM idempotency_record WHERE operation = 'plans.cancel' AND state = 'COMPLETED'")).isOne();

        getAs(MEMBER_ID, "/api/v1/plans/{planId}", PLAN_ID)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("CANCELLED"))
                .andExpect(jsonPath("$.requirements.area.code").value("BGC"));
        getAs(MEMBER_ID, "/api/v1/plans/{planId}/preferences", PLAN_ID)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].preference.accountId").value(MEMBER_ID.toString()));
    }

    @Test
    void organizerCancelsOpenRequestWithPointerEventsAuditAndReplayInOneTransaction() throws Exception {
        insertOpenRequestWithRecipients();

        var first = cancelAs(ORGANIZER_ID, "cancel-open", "\"2\"")
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"3\""))
                .andExpect(jsonPath("$.planId").value(PLAN_ID.toString()))
                .andExpect(jsonPath("$.state").value("CANCELLED"))
                .andExpect(jsonPath("$.version").value(3))
                .andReturn().getResponse();

        assertThat(planState()).isEqualTo(PlanState.CANCELLED);
        assertThat(planVersion()).isEqualTo(3);
        assertThat(currentRequestId()).isNull();
        assertThat(requestState()).isEqualTo("CANCELLED");
        assertThat(requestHasTerminalTimestamp()).isTrue();
        assertThat(recipientCount()).isEqualTo(2);
        assertThat(cancelledBusinessKeys()).containsExactly(
                "ProviderRequestCancelled:" + REQUEST_ID + ":" + FIRST_PROVIDER_ID,
                "ProviderRequestCancelled:" + REQUEST_ID + ":" + SECOND_PROVIDER_ID);
        assertSafeCancellationPayloads();
        assertThat(count("SELECT count(*) FROM audit_event WHERE action = 'plan.cancelled' AND plan_id = :planId"))
                .isOne();
        assertThat(count("SELECT count(*) FROM idempotency_record WHERE operation = 'plans.cancel' AND state = 'COMPLETED'"))
                .isOne();

        var replay = cancelAs(ORGANIZER_ID, "cancel-open", "\"2\"")
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"3\""))
                .andReturn().getResponse();
        assertThat(replay.getContentAsString()).isEqualTo(first.getContentAsString());
        assertThat(cancelledBusinessKeys()).hasSize(2);

        getAs(MEMBER_ID, "/api/v1/plans/{planId}/published-requests/" + REQUEST_ID, PLAN_ID)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("CANCELLED"))
                .andExpect(jsonPath("$.actionable").value(false));
    }

    @Test
    void openRequestCancellationRollsBackPlanRequestEventsAuditAndReplayWhenAuditFails() throws Exception {
        insertOpenRequestWithRecipients();
        installAuditFailureTrigger();

        cancelAs(ORGANIZER_ID, "cancel-open-rollback", "\"2\"")
                .andExpect(status().isInternalServerError());

        assertThat(planState()).isEqualTo(PlanState.OPEN_FOR_OFFERS);
        assertThat(planVersion()).isEqualTo(2);
        assertThat(currentRequestId()).isEqualTo(REQUEST_ID);
        assertThat(requestState()).isEqualTo("OPEN");
        assertThat(requestHasTerminalTimestamp()).isFalse();
        assertThat(recipientCount()).isEqualTo(2);
        assertThat(cancelledBusinessKeys()).isEmpty();
        assertThat(count("SELECT count(*) FROM audit_event")).isZero();
        assertThat(count("SELECT count(*) FROM idempotency_record")).isZero();
    }

    @Test
    void rejectsRolePrivacyAndEveryPlanPreconditionBeforeChangingState() throws Exception {
        cancelAs(MEMBER_ID, "member-cancel", "\"1\"")
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN_ROLE"));
        cancelAs(OUTSIDER_ID, "outsider-cancel", "\"1\"")
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("PRIVATE_RESOURCE_NOT_FOUND"));
        cancelAs(ORGANIZER_ID, "missing-etag", null)
                .andExpect(status().isPreconditionRequired()).andExpect(jsonPath("$.code").value("PRECONDITION_REQUIRED"));
        cancelAs(ORGANIZER_ID, "invalid-etag", "1")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_PRECONDITION"));
        cancelAs(ORGANIZER_ID, "stale-etag", "\"2\"")
                .andExpect(status().isPreconditionFailed()).andExpect(jsonPath("$.code").value("PRECONDITION_FAILED"));
        mockMvc.perform(post("/api/v1/plans/{planId}/cancellation", PLAN_ID)
                        .with(actor(ORGANIZER_ID))
                        .header("If-Match", "\"1\""))
                .andExpect(status().isBadRequest());

        assertThat(planState()).isEqualTo(PlanState.COLLABORATING);
        assertThat(planVersion()).isOne();
        assertThat(count("SELECT count(*) FROM audit_event")).isZero();
        assertThat(count("SELECT count(*) FROM idempotency_record")).isZero();
    }

    @Test
    void exactReplayPrecedesVersionValidationAndReturnsItsOriginalEtag() throws Exception {
        var first = cancelAs(ORGANIZER_ID, "cancel-replay", "\"1\"")
                .andExpect(status().isOk())
                .andReturn().getResponse();
        jdbcClient.sql("UPDATE planning_plan SET version = 3 WHERE plan_id = :planId")
                .param("planId", PLAN_ID).update();

        var replay = cancelAs(ORGANIZER_ID, "cancel-replay", "\"1\"")
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"2\""))
                .andReturn().getResponse();

        assertThat(replay.getContentAsString()).isEqualTo(first.getContentAsString());
        assertThat(count("SELECT count(*) FROM audit_event WHERE action = 'plan.cancelled'")).isOne();
    }

    @Test
    void rejectsConflictingKeyReuseAndNewKeysAgainstTheCancelledPlanInDocumentedOrder() throws Exception {
        cancelAs(ORGANIZER_ID, "cancel-conflict", "\"1\"").andExpect(status().isOk());
        cancelAs(ORGANIZER_ID, "cancel-conflict", "\"2\"")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
        cancelAs(ORGANIZER_ID, "cancel-stale-new-key", "\"1\"")
                .andExpect(status().isPreconditionFailed()).andExpect(jsonPath("$.code").value("PRECONDITION_FAILED"));
        cancelAs(ORGANIZER_ID, "cancel-current-new-key", "\"2\"")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("INVALID_PLAN_STATE"));

        assertThat(count("SELECT count(*) FROM audit_event WHERE action = 'plan.cancelled'")).isOne();
        assertThat(count("SELECT count(*) FROM idempotency_record WHERE operation = 'plans.cancel' AND state = 'COMPLETED'")).isOne();
    }

    @Test
    void rollsBackThePlanTransitionAndIdempotencyCompletionWhenAuditAppendFails() throws Exception {
        installAuditFailureTrigger();

        cancelAs(ORGANIZER_ID, "cancel-rollback", "\"1\"")
                .andExpect(status().isInternalServerError());

        assertThat(planState()).isEqualTo(PlanState.COLLABORATING);
        assertThat(planVersion()).isOne();
        assertThat(count("SELECT count(*) FROM audit_event")).isZero();
        assertThat(count("SELECT count(*) FROM idempotency_record")).isZero();
    }

    @Test
    void cancellationAndRequirementReplacementHaveOneOrderedOutcome() throws Exception {
        var barrier = new CyclicBarrier(2);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var cancellation = executor.submit(() -> cancelAfterBarrier(barrier));
            var replacement = executor.submit(() -> replaceRequirementsAfterBarrier(barrier));
            var results = List.of(cancellation.get(10, TimeUnit.SECONDS), replacement.get(10, TimeUnit.SECONDS));
            assertThat(results.stream().filter(PlanRepresentation.class::isInstance).count()
                    + results.stream().filter(com.builtbyjuls.arat.planning.api.RequirementRepresentation.class::isInstance).count()).isOne();
            var failures = results.stream().filter(Exception.class::isInstance).toList();
            assertThat(failures).hasSize(1);
            assertThat(isPreconditionFailure(failures.getFirst())).isTrue();
            if (results.stream().anyMatch(PlanRepresentation.class::isInstance)) {
                assertThat(failures.getFirst()).isInstanceOf(RequirementReplacementException.class)
                        .extracting(RequirementReplacementException.class::cast)
                        .extracting(RequirementReplacementException::reason)
                        .isEqualTo(RequirementReplacementException.Reason.PRECONDITION_FAILED);
                assertThat(planState()).isEqualTo(PlanState.CANCELLED);
                assertThat(planTitle()).isEqualTo("Cancellation plan");
                assertThat(candidateWindowStart()).isEqualTo(OffsetDateTime.parse("2027-01-09T09:00:00Z"));
                assertThat(count("SELECT count(*) FROM audit_event WHERE action = 'plan.cancelled'")).isOne();
                assertThat(count("SELECT count(*) FROM audit_event WHERE action = 'plan.requirements.replaced'")).isZero();
                assertThat(count("SELECT count(*) FROM idempotency_record WHERE operation = 'plans.cancel' AND state = 'COMPLETED'")).isOne();
            } else {
                assertThat(failures.getFirst()).isInstanceOf(PlanCancellationException.class)
                        .extracting(PlanCancellationException.class::cast)
                        .extracting(PlanCancellationException::reason)
                        .isEqualTo(PlanCancellationException.Reason.PRECONDITION_FAILED);
                assertThat(planState()).isEqualTo(PlanState.COLLABORATING);
                assertThat(planTitle()).isEqualTo("Replacement race plan");
                assertThat(candidateWindowStart()).isEqualTo(OffsetDateTime.parse("2027-01-09T09:30:00Z"));
                assertThat(count("SELECT count(*) FROM audit_event WHERE action = 'plan.cancelled'")).isZero();
                assertThat(count("SELECT count(*) FROM audit_event WHERE action = 'plan.requirements.replaced'")).isOne();
                assertThat(count("SELECT count(*) FROM idempotency_record WHERE operation = 'plans.cancel' AND state = 'COMPLETED'")).isZero();
            }
        }
        assertThat(planVersion()).isEqualTo(2);
    }

    @Test
    void cancellationAndPreferenceReplacementHaveOneOrderedOutcome() throws Exception {
        insertPreference();
        var barrier = new CyclicBarrier(2);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var cancellation = executor.submit(() -> cancelAfterBarrier(barrier));
            var replacement = executor.submit(() -> replacePreferenceAfterBarrier(barrier));
            assertThat(cancellation.get(10, TimeUnit.SECONDS)).isInstanceOf(PlanRepresentation.class);
            var preferenceResult = replacement.get(10, TimeUnit.SECONDS);
            if (preferenceResult instanceof com.builtbyjuls.arat.planning.api.PreferenceRepresentation preference) {
                assertThat(preference.version()).isEqualTo(2);
                assertThat(preference.privateNote()).isEqualTo("Replacement preference.");
                assertThat(preferenceVersion()).isEqualTo(2);
                assertThat(preferenceNote()).isEqualTo("Replacement preference.");
            } else {
                assertThat(preferenceResult).isInstanceOf(com.builtbyjuls.arat.planning.api.PreferenceException.class)
                        .extracting(com.builtbyjuls.arat.planning.api.PreferenceException.class::cast)
                        .extracting(com.builtbyjuls.arat.planning.api.PreferenceException::reason)
                        .isEqualTo(com.builtbyjuls.arat.planning.api.PreferenceException.Reason.INVALID_PLAN_STATE);
                assertThat(preferenceVersion()).isOne();
                assertThat(preferenceNoteIsNull()).isTrue();
            }
        }
        assertThat(planState()).isEqualTo(PlanState.CANCELLED);
        assertThat(planVersion()).isEqualTo(2);
        assertThat(count("SELECT count(*) FROM audit_event WHERE action = 'plan.cancelled'")).isOne();
        assertThat(count("SELECT count(*) FROM idempotency_record WHERE operation = 'plans.cancel' AND state = 'COMPLETED'")).isOne();
        assertThat(count("SELECT count(*) FROM planning_plan_preference WHERE plan_id = :planId")).isOne();
    }

    @Test
    void publishesTheCancellationContractInOpenApi() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/v1/plans/{planId}/cancellation'].post.operationId").value("cancelCollaborativePlan"))
                .andExpect(jsonPath("$.paths['/api/v1/plans/{planId}/cancellation'].post.parameters[?(@.name == 'Idempotency-Key')].required").value(true))
                .andExpect(jsonPath("$.paths['/api/v1/plans/{planId}/cancellation'].post.parameters[?(@.name == 'If-Match')].required").value(true))
                .andExpect(jsonPath("$.paths['/api/v1/plans/{planId}/cancellation'].post.responses['428']").exists());
    }

    private ResultActions cancelAs(UUID actorId, String key, String etag) throws Exception {
        var request = post("/api/v1/plans/{planId}/cancellation", PLAN_ID)
                .with(actor(actorId))
                .header("Idempotency-Key", key)
                .header(CorrelationIdFilter.HEADER_NAME, "cancellation-test");
        if (etag != null) {
            request.header("If-Match", etag);
        }
        return mockMvc.perform(request);
    }

    private RequestPostProcessor actor(UUID accountId) {
        return SecurityMockMvcRequestPostProcessors.authentication(new TestingAuthenticationToken(
                new AuthenticatedActor(accountId, Set.of()), null, "ROLE_USER"));
    }

    private ResultActions getAs(UUID actorId, String path, UUID planId) throws Exception {
        return mockMvc.perform(get(path, planId).with(actor(actorId)));
    }

    private Object cancelAfterBarrier(CyclicBarrier barrier) throws Exception {
        barrier.await(10, TimeUnit.SECONDS);
        try {
            return planCancellationService.cancel(new CancelPlanCommand(
                    ORGANIZER_ID, PLAN_ID, 1, "cancel-race", "cancellation-race"));
        } catch (PlanCancellationException exception) {
            return exception;
        }
    }

    private Object replaceRequirementsAfterBarrier(CyclicBarrier barrier) throws Exception {
        barrier.await(10, TimeUnit.SECONDS);
        try {
            return requirementReplacementService.replace(new ReplaceRequirementsCommand(
                    ORGANIZER_ID,
                    PLAN_ID,
                    1,
                    new RequirementReplacementRequest(
                            "Replacement race plan",
                            ActivityCategory.COURT,
                            "Asia/Manila",
                            List.of(new RequirementReplacementRequest.CandidateWindowRequest(
                                    WINDOW_ID,
                                    OffsetDateTime.parse("2027-01-09T09:30:00Z"),
                                    OffsetDateTime.parse("2027-01-09T11:30:00Z"))),
                            new com.builtbyjuls.arat.planning.api.CreatePlanRequest.AreaRequest("BGC", 5),
                            new com.builtbyjuls.arat.planning.api.CreatePlanRequest.HeadcountRequest(4, 10),
                            null,
                            List.of(),
                            null,
                            Map.of()),
                    "cancellation-race"));
        } catch (RequirementReplacementException exception) {
            return exception;
        }
    }

    private Object replacePreferenceAfterBarrier(CyclicBarrier barrier) throws Exception {
        barrier.await(10, TimeUnit.SECONDS);
        try {
            return planPreferenceService.replace(new com.builtbyjuls.arat.planning.api.ReplacePreferenceCommand(
                    MEMBER_ID,
                    PLAN_ID,
                    1,
                    preferenceRequest("Replacement preference.")));
        } catch (com.builtbyjuls.arat.planning.api.PreferenceException exception) {
            return exception;
        }
    }

    private boolean isPreconditionFailure(Object outcome) {
        if (outcome instanceof PlanCancellationException cancellation) {
            return cancellation.reason() == PlanCancellationException.Reason.PRECONDITION_FAILED;
        }
        if (outcome instanceof RequirementReplacementException replacement) {
            return replacement.reason() == RequirementReplacementException.Reason.PRECONDITION_FAILED;
        }
        return false;
    }

    private void insertPlan() {
        jdbcClient.sql("""
                        INSERT INTO planning_plan (plan_id, group_id, title, state, created_by_account_id, version)
                        VALUES (:planId, :groupId, 'Cancellation plan', 'COLLABORATING', :organizerId, 1)
                        """)
                .param("planId", PLAN_ID).param("groupId", GROUP_ID).param("organizerId", ORGANIZER_ID).update();
        jdbcClient.sql("""
                        INSERT INTO planning_requirement_draft (
                            plan_id, category, time_zone, area_code, radius_km, minimum_headcount,
                            maximum_headcount, category_attributes
                        ) VALUES (:planId, 'COURT', 'Asia/Manila', 'BGC', 5, 4, 10, '{}'::jsonb)
                        """).param("planId", PLAN_ID).update();
        jdbcClient.sql("""
                        INSERT INTO planning_candidate_window (candidate_window_id, plan_id, sort_order, starts_at, ends_at)
                        VALUES (:windowId, :planId, 1, '2027-01-09T09:00:00Z', '2027-01-09T11:00:00Z')
                        """).param("windowId", WINDOW_ID).param("planId", PLAN_ID).update();
    }

    private void insertPreference() {
        jdbcClient.sql("""
                        INSERT INTO planning_plan_preference (
                            plan_id, account_id, basis_plan_version, attendance, guest_count, version
                        ) VALUES (:planId, :accountId, 1, 'JOINING', 0, 1)
                """).param("planId", PLAN_ID).param("accountId", MEMBER_ID).update();
    }

    private void insertOpenRequestWithRecipients() {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            jdbcClient.sql("""
                        INSERT INTO planning_published_request (
                            request_id, plan_id, request_version, state, distribution_mode,
                            category, time_zone, area_code, radius_km, requested_starts_at,
                            requested_ends_at, minimum_headcount, maximum_headcount,
                            budget_currency, budget_minimum_minor_units, budget_maximum_minor_units,
                            must_haves, provider_safe_notes, category_attributes, offer_deadline,
                            published_by_account_id, published_at
                        ) VALUES (
                            :requestId, :planId, 1, 'OPEN', 'MATCHED_POOL',
                            'COURT', 'Asia/Manila', 'BGC', 5,
                            statement_timestamp() + interval '2 days',
                            statement_timestamp() + interval '2 days 2 hours',
                            4, 10, 'PHP', 100000, 250000,
                            ARRAY['parking']::varchar[], 'Indoor court preferred.',
                            '{"courtCount":2}'::jsonb,
                            statement_timestamp() + interval '1 day',
                            :organizerId, statement_timestamp()
                        )
                        """)
                .param("requestId", REQUEST_ID)
                .param("planId", PLAN_ID)
                .param("organizerId", ORGANIZER_ID)
                .update();
            jdbcClient.sql("""
                        UPDATE planning_plan
                        SET state = 'OPEN_FOR_OFFERS', current_request_id = :requestId, version = 2
                        WHERE plan_id = :planId
                        """)
                .param("requestId", REQUEST_ID)
                .param("planId", PLAN_ID)
                .update();
        });
        for (var providerId : List.of(FIRST_PROVIDER_ID, SECOND_PROVIDER_ID)) {
            jdbcClient.sql("""
                            INSERT INTO provider_organization (
                                provider_id, display_name, status, verification_status,
                                version, eligibility_version
                            ) VALUES (:providerId, :displayName, 'ACTIVE', 'VERIFIED', 1, 1)
                            """)
                    .param("providerId", providerId)
                    .param("displayName", "Cancellation provider " + providerId)
                    .update();
            jdbcClient.sql("""
                            INSERT INTO marketplace_request_recipient (
                                published_request_id, provider_id, provider_eligibility_version,
                                source, access_state, created_at
                            ) VALUES (:requestId, :providerId, 1, 'MATCH_RULE', 'ACTIVE', statement_timestamp())
                            """)
                    .param("requestId", REQUEST_ID)
                    .param("providerId", providerId)
                    .update();
        }
    }

    private void installAuditFailureTrigger() {
        jdbcClient.sql("""
                        CREATE FUNCTION test_fail_plan_cancelled_audit()
                        RETURNS trigger LANGUAGE plpgsql AS $$
                        BEGIN RAISE EXCEPTION 'forced cancellation audit failure'; END;
                        $$
                        """).update();
        jdbcClient.sql("""
                        CREATE TRIGGER fail_plan_cancelled_audit
                        BEFORE INSERT ON audit_event FOR EACH ROW
                        WHEN (NEW.action = 'plan.cancelled')
                        EXECUTE FUNCTION test_fail_plan_cancelled_audit()
                        """).update();
    }

    private CreatePreferenceRequest preferenceRequest(String note) {
        return new CreatePreferenceRequest(
                1L, Attendance.JOINING, 0, List.of(WINDOW_ID), null, List.of("parking"), note);
    }

    private PlanState planState() {
        return PlanState.valueOf(jdbcClient.sql("SELECT state FROM planning_plan WHERE plan_id = :planId")
                .param("planId", PLAN_ID).query(String.class).single());
    }

    private long planVersion() {
        return jdbcClient.sql("SELECT version FROM planning_plan WHERE plan_id = :planId")
                .param("planId", PLAN_ID).query(Long.class).single();
    }

    private UUID currentRequestId() {
        return jdbcClient.sql("SELECT current_request_id FROM planning_plan WHERE plan_id = :planId")
                .param("planId", PLAN_ID).query(UUID.class).optional().orElse(null);
    }

    private String requestState() {
        return jdbcClient.sql("SELECT state FROM planning_published_request WHERE request_id = :requestId")
                .param("requestId", REQUEST_ID).query(String.class).single();
    }

    private boolean requestHasTerminalTimestamp() {
        return jdbcClient.sql("SELECT closed_at IS NOT NULL FROM planning_published_request WHERE request_id = :requestId")
                .param("requestId", REQUEST_ID).query(Boolean.class).single();
    }

    private long recipientCount() {
        return jdbcClient.sql("SELECT count(*) FROM marketplace_request_recipient WHERE published_request_id = :requestId")
                .param("requestId", REQUEST_ID).query(Long.class).single();
    }

    private List<String> cancelledBusinessKeys() {
        return jdbcClient.sql("""
                        SELECT business_key
                        FROM messaging_outbox
                        WHERE aggregate_id = :requestId AND event_type = 'ProviderRequestCancelled'
                        ORDER BY business_key
                        """)
                .param("requestId", REQUEST_ID)
                .query(String.class)
                .list();
    }

    private void assertSafeCancellationPayloads() {
        var payloads = jdbcClient.sql("""
                        SELECT payload::text
                        FROM messaging_outbox
                        WHERE aggregate_id = :requestId AND event_type = 'ProviderRequestCancelled'
                        """)
                .param("requestId", REQUEST_ID)
                .query(String.class)
                .list();
        assertThat(payloads).hasSize(2).allSatisfy(payload -> {
            assertThat(payload).contains("requestId", "providerId");
            assertThat(payload.toLowerCase()).doesNotContain(
                    "group", "member", "employer", "note", "contact", "title", "preference", "attendance");
        });
    }

    private String planTitle() {
        return jdbcClient.sql("SELECT title FROM planning_plan WHERE plan_id = :planId")
                .param("planId", PLAN_ID).query(String.class).single();
    }

    private OffsetDateTime candidateWindowStart() {
        return jdbcClient.sql("SELECT starts_at FROM planning_candidate_window WHERE candidate_window_id = :windowId")
                .param("windowId", WINDOW_ID).query(OffsetDateTime.class).single();
    }

    private long preferenceVersion() {
        return jdbcClient.sql("SELECT version FROM planning_plan_preference WHERE plan_id = :planId AND account_id = :accountId")
                .param("planId", PLAN_ID).param("accountId", MEMBER_ID).query(Long.class).single();
    }

    private String preferenceNote() {
        return jdbcClient.sql("SELECT private_note FROM planning_plan_preference WHERE plan_id = :planId AND account_id = :accountId")
                .param("planId", PLAN_ID).param("accountId", MEMBER_ID).query(String.class).single();
    }

    private boolean preferenceNoteIsNull() {
        return jdbcClient.sql("SELECT private_note IS NULL FROM planning_plan_preference WHERE plan_id = :planId AND account_id = :accountId")
                .param("planId", PLAN_ID).param("accountId", MEMBER_ID).query(Boolean.class).single();
    }

    private long count(String sql) {
        return jdbcClient.sql(sql).param("planId", PLAN_ID).query(Long.class).single();
    }
}

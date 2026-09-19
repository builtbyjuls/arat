package com.builtbyjuls.arat.planning.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.builtbyjuls.arat.PostgreSqlIntegrationTest;
import com.builtbyjuls.arat.groups.api.GroupMembershipAccess;
import com.builtbyjuls.arat.groups.api.RemoveGroupMemberCommand;
import com.builtbyjuls.arat.groups.application.MembershipExitService;
import com.builtbyjuls.arat.identity.testing.WithAratActor;
import com.builtbyjuls.arat.planning.api.CreatePlanCommand;
import com.builtbyjuls.arat.planning.api.CreatePlanRequest;
import com.builtbyjuls.arat.web.CorrelationIdFilter;
import java.time.OffsetDateTime;
import java.time.Duration;
import java.util.function.BooleanSupplier;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PlanCreationIT extends PostgreSqlIntegrationTest {

    private static final UUID ORGANIZER_ID = UUID.fromString("71000000-0000-4000-8000-000000000001");
    private static final UUID MEMBER_ID = UUID.fromString("71000000-0000-4000-8000-000000000002");
    private static final UUID OUTSIDER_ID = UUID.fromString("71000000-0000-4000-8000-000000000003");
    private static final UUID GROUP_ID = UUID.fromString("72000000-0000-4000-8000-000000000001");
    private static final String CORRELATION_ID = "plan-create-test-123";

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private CorrelationIdFilter correlationIdFilter;

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private PlanCreationService planCreationService;

    @Autowired
    private MembershipExitService membershipExitService;

    @Autowired
    private GroupMembershipAccess groupMembershipAccess;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private DataSource dataSource;

    private MockMvc mockMvc;

    @BeforeEach
    void prepareDatabase() {
        removeAuditFailureTrigger();
        jdbcClient.sql("DELETE FROM audit_event").update();
        jdbcClient.sql("DELETE FROM idempotency_record").update();
        jdbcClient.sql("DELETE FROM planning_requirement_must_have").update();
        jdbcClient.sql("DELETE FROM planning_candidate_window").update();
        jdbcClient.sql("DELETE FROM planning_requirement_draft").update();
        jdbcClient.sql("DELETE FROM planning_plan").update();
        jdbcClient.sql("DELETE FROM group_membership").update();
        jdbcClient.sql("DELETE FROM group_account").update();
        jdbcClient.sql("DELETE FROM identity_account WHERE account_id IN (:organizerId, :memberId, :outsiderId)")
                .param("organizerId", ORGANIZER_ID)
                .param("memberId", MEMBER_ID)
                .param("outsiderId", OUTSIDER_ID)
                .update();
        jdbcClient.sql("""
                        INSERT INTO identity_account (account_id, display_name) VALUES
                            (:organizerId, 'Plan organizer'),
                            (:memberId, 'Plan member'),
                            (:outsiderId, 'Plan outsider')
                        """)
                .param("organizerId", ORGANIZER_ID)
                .param("memberId", MEMBER_ID)
                .param("outsiderId", OUTSIDER_ID)
                .update();
        jdbcClient.sql("""
                        INSERT INTO group_account (group_id, name, description, status, created_by_account_id)
                        VALUES (:groupId, 'Planning group', '', 'ACTIVE', :organizerId)
                        """)
                .param("groupId", GROUP_ID)
                .param("organizerId", ORGANIZER_ID)
                .update();
        jdbcClient.sql("""
                        INSERT INTO group_membership (group_id, account_id, role, status, joined_at)
                        VALUES
                            (:groupId, :organizerId, 'ORGANIZER', 'ACTIVE', statement_timestamp()),
                            (:groupId, :memberId, 'MEMBER', 'ACTIVE', statement_timestamp())
                        """)
                .param("groupId", GROUP_ID)
                .param("organizerId", ORGANIZER_ID)
                .param("memberId", MEMBER_ID)
                .update();
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .addFilters(correlationIdFilter)
                .apply(springSecurity())
                .build();
    }

    @AfterEach
    void removeAuditFailureTrigger() {
        jdbcClient.sql("DROP TRIGGER IF EXISTS fail_plan_created_audit ON audit_event").update();
        jdbcClient.sql("DROP FUNCTION IF EXISTS test_fail_plan_created_audit()").update();
        jdbcClient.sql("DROP TRIGGER IF EXISTS block_plan_creation ON planning_plan").update();
        jdbcClient.sql("DROP FUNCTION IF EXISTS test_block_plan_creation()").update();
    }

    @Test
    @WithAratActor(accountId = "71000000-0000-4000-8000-000000000001")
    void organizerCreatesACompleteCollaborativePlanAtomically() throws Exception {
        var response = create("organizer-plan", validRequest())
                .andExpect(status().isCreated())
                .andExpect(header().string("ETag", "\"1\""))
                .andExpect(header().string("Location", org.hamcrest.Matchers.matchesPattern("/api/v1/plans/[0-9a-f-]{36}")))
                .andExpect(jsonPath("$.state").value("COLLABORATING"))
                .andExpect(jsonPath("$.createdByAccountId").value(ORGANIZER_ID.toString()))
                .andExpect(jsonPath("$.version").value(1))
                .andReturn()
                .getResponse();

        var planId = UUID.fromString(new ObjectMapper().readTree(response.getContentAsString()).path("planId").asString());
        assertThat(count("SELECT count(*) FROM planning_requirement_draft WHERE plan_id = :planId", planId)).isOne();
        assertThat(count("SELECT count(*) FROM planning_candidate_window WHERE plan_id = :planId", planId)).isOne();
        assertThat(count("SELECT count(*) FROM planning_requirement_must_have WHERE plan_id = :planId", planId)).isEqualTo(2);
        assertThat(count("SELECT count(*) FROM audit_event WHERE action = 'plan.created' AND plan_id = :planId", planId)).isOne();
        assertThat(count("SELECT count(*) FROM idempotency_record WHERE operation = 'plans.create' AND state = 'COMPLETED'")).isOne();
    }

    @Test
    @WithAratActor(accountId = "71000000-0000-4000-8000-000000000002")
    void ordinaryActiveMemberCanCreateAPlan() throws Exception {
        create("member-plan", validRequest())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.createdByAccountId").value(MEMBER_ID.toString()));
    }

    @Test
    @WithAratActor(accountId = "71000000-0000-4000-8000-000000000001")
    void exactReplayReturnsOneOriginalPlanAndNoDuplicateEffects() throws Exception {
        var first = create("plan-replay", validRequest()).andExpect(status().isCreated()).andReturn().getResponse();
        var replay = create("plan-replay", validRequest())
                .andExpect(status().isCreated())
                .andExpect(header().string("ETag", first.getHeader("ETag")))
                .andExpect(header().string("Location", first.getHeader("Location")))
                .andReturn()
                .getResponse();

        assertThat(replay.getContentAsString()).isEqualTo(first.getContentAsString());
        assertThat(count("SELECT count(*) FROM planning_plan")).isOne();
        assertThat(count("SELECT count(*) FROM planning_requirement_draft")).isOne();
        assertThat(count("SELECT count(*) FROM planning_candidate_window")).isOne();
        assertThat(count("SELECT count(*) FROM planning_requirement_must_have")).isEqualTo(2);
        assertThat(count("SELECT count(*) FROM audit_event WHERE action = 'plan.created'")).isOne();
    }

    @Test
    @WithAratActor(accountId = "71000000-0000-4000-8000-000000000001")
    void rollsBackPlanChildrenAuditAndIdempotencyRecordWhenAuditFails() throws Exception {
        jdbcClient.sql("""
                        CREATE FUNCTION test_fail_plan_created_audit()
                        RETURNS trigger LANGUAGE plpgsql AS $$
                        BEGIN RAISE EXCEPTION 'forced audit failure'; END;
                        $$
                        """).update();
        jdbcClient.sql("""
                        CREATE TRIGGER fail_plan_created_audit
                        BEFORE INSERT ON audit_event FOR EACH ROW
                        WHEN (NEW.action = 'plan.created')
                        EXECUTE FUNCTION test_fail_plan_created_audit()
                        """).update();

        create("plan-rollback", validRequest())
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"));

        assertThat(count("SELECT count(*) FROM planning_plan")).isZero();
        assertThat(count("SELECT count(*) FROM planning_requirement_draft")).isZero();
        assertThat(count("SELECT count(*) FROM planning_candidate_window")).isZero();
        assertThat(count("SELECT count(*) FROM planning_requirement_must_have")).isZero();
        assertThat(count("SELECT count(*) FROM audit_event")).isZero();
        assertThat(count("SELECT count(*) FROM idempotency_record")).isZero();
    }

    @Test
    @WithAratActor(accountId = "71000000-0000-4000-8000-000000000003")
    void outsiderReceivesThePrivateNotFoundShape() throws Exception {
        create("outsider-plan", validRequest())
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("PRIVATE_RESOURCE_NOT_FOUND"));
    }

    @Test
    @WithAratActor(accountId = "71000000-0000-4000-8000-000000000002")
    void leftAndRemovedMembersReceiveThePrivateNotFoundShape() throws Exception {
        jdbcClient.sql("""
                        UPDATE group_membership
                        SET status = 'LEFT', ended_at = statement_timestamp()
                        WHERE group_id = :groupId AND account_id = :memberId
                        """)
                .param("groupId", GROUP_ID)
                .param("memberId", MEMBER_ID)
                .update();
        create("left-plan", validRequest()).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("PRIVATE_RESOURCE_NOT_FOUND"));
        jdbcClient.sql("""
                        UPDATE group_membership
                        SET status = 'REMOVED', ended_at = statement_timestamp()
                        WHERE group_id = :groupId AND account_id = :memberId
                        """)
                .param("groupId", GROUP_ID)
                .param("memberId", MEMBER_ID)
                .update();
        create("removed-plan", validRequest()).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("PRIVATE_RESOURCE_NOT_FOUND"));
    }

    @Test
    @WithAratActor(accountId = "71000000-0000-4000-8000-000000000001")
    void rejectsUnknownFieldsAndAllBoundedScalarAttributeForms() throws Exception {
        create("unknown-field", validRequest().replace("\"title\"", "\"unexpected\":true,\"title\""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));

        for (var attributes : List.of(
                "{\"bad_key\":true}",
                "{\"valid\":null}",
                "{\"valid\":1.5}",
                "{\"valid\":[]}",
                "{\"valid\":{}}",
                "{\"valid\":-1}",
                "{\"valid\":1000001}",
                "{\"valid\":\"\"}",
                "{\"valid\":\"" + "x".repeat(121) + "\"}",
                attributesWith21Entries())) {
            create("invalid-attributes-" + Integer.toUnsignedString(attributes.hashCode()), validRequestWithAttributes(attributes))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        }
    }

    @Test
    @WithAratActor(accountId = "71000000-0000-4000-8000-000000000001")
    void validatesAllFrozenRequestBounds() throws Exception {
        for (var request : List.of(
                validRequest().replace("Friday badminton", ""),
                validRequest().replace("Friday badminton", "x".repeat(121)),
                validRequest().replace("\"Asia/Manila\"", "\"+08:00\""),
                validRequest().replace("\"BGC\"", "\"\""),
                validRequest().replace("\"BGC\"", "\"" + "x".repeat(65) + "\""),
                validRequest().replace("\"radiusKm\":5", "\"radiusKm\":0"),
                validRequest().replace("\"radiusKm\":5", "\"radiusKm\":101"),
                validRequest().replace("\"minimum\":4", "\"minimum\":0"),
                validRequest().replace("\"maximum\":10", "\"maximum\":101"),
                validRequest().replace("\"minimum\":4,\"maximum\":10", "\"minimum\":11,\"maximum\":10"),
                validRequest().replace("\"startAt\":\"2027-01-09T09:00:00Z\"", "\"startAt\":\"2027-01-09T11:00:00Z\""),
                validRequest().replace("\"currency\":\"PHP\"", "\"currency\":\"USD\""),
                validRequest().replace("\"minimumAmount\":\"0.00\"", "\"minimumAmount\":\"0.0\""),
                validRequest().replace("\"maximumAmount\":\"2500.00\"", "\"maximumAmount\":\"1000000.01\""),
                validRequest().replace("\"minimumAmount\":\"0.00\",\"maximumAmount\":\"2500.00\"", "\"minimumAmount\":\"2500.01\",\"maximumAmount\":\"2500.00\""),
                validRequest().replace("\"candidateWindows\":[{\"startAt\":\"2027-01-09T09:00:00Z\",\"endAt\":\"2027-01-09T11:00:00Z\"}]", "\"candidateWindows\":[]"),
                validRequest().replace("\"candidateWindows\":[{\"startAt\":\"2027-01-09T09:00:00Z\",\"endAt\":\"2027-01-09T11:00:00Z\"}]", candidateWindowsWith11Entries()),
                validRequest().replace("\"mustHaves\":[\"parking\",\"shower\"]", "\"mustHaves\":[\"parking\",\"parking\"]"),
                validRequest().replace("\"mustHaves\":[\"parking\",\"shower\"]", "\"mustHaves\":[\"\"]"),
                validRequest().replace("\"mustHaves\":[\"parking\",\"shower\"]", "\"mustHaves\":[\"" + "x".repeat(121) + "\"]"),
                validRequest().replace("\"mustHaves\":[\"parking\",\"shower\"]", mustHavesWith21Entries()),
                validRequest().replace("\"providerSafeNotes\":\"Indoor court preferred.\"", "\"providerSafeNotes\":\"" + "x".repeat(1001) + "\""))) {
            create("invalid-bounds-" + Integer.toUnsignedString(request.hashCode()), request)
                    .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        }

        create("null-window", validRequest().replace(
                "[{\"startAt\":\"2027-01-09T09:00:00Z\",\"endAt\":\"2027-01-09T11:00:00Z\"}]", "[null]"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        create("fractional-radius", validRequest().replace("\"radiusKm\":5", "\"radiusKm\":5.9"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
        create("fractional-headcount", validRequest().replace("\"minimum\":4", "\"minimum\":4.9"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
        assertThat(count("SELECT count(*) FROM planning_plan")).isZero();
    }

    @Test
    void concurrentRetriesAndMembershipRemovalHaveOnlyLegalOutcomes() throws Exception {
        var command = command(ORGANIZER_ID, "concurrent-plan");
        var retries = new CyclicBarrier(2);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> createAfterBarrier(command, retries));
            var second = executor.submit(() -> createAfterBarrier(command, retries));
            assertThat(first.get(10, TimeUnit.SECONDS).planId()).isEqualTo(second.get(10, TimeUnit.SECONDS).planId());
        }
        assertThat(count("SELECT count(*) FROM planning_plan")).isOne();
        assertThat(count("SELECT count(*) FROM audit_event WHERE action = 'plan.created'")).isOne();

        prepareDatabase();
        var removalRace = new CyclicBarrier(2);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var create = executor.submit(() -> createAfterBarrier(command(MEMBER_ID, "member-removal-race"), removalRace));
            var remove = executor.submit(() -> removeAfterBarrier(removalRace));
            remove.get(10, TimeUnit.SECONDS);
            try {
                create.get(10, TimeUnit.SECONDS);
            } catch (java.util.concurrent.ExecutionException exception) {
                assertThat(exception.getCause()).isInstanceOf(com.builtbyjuls.arat.planning.api.PlanCreationException.class);
            }
        }
        assertThat(count("SELECT count(*) FROM planning_plan")).isBetween(0L, 1L);
        assertThat(groupMemberCount("SELECT count(*) FROM group_membership WHERE group_id = :groupId AND account_id = :memberId AND status = 'REMOVED'")).isOne();
    }

    @Test
    void planCreationHoldsTheGroupLockWhileRemovalWaits() throws Exception {
        jdbcClient.sql("""
                        CREATE FUNCTION test_block_plan_creation()
                        RETURNS trigger LANGUAGE plpgsql AS $$
                        BEGIN
                            PERFORM pg_advisory_xact_lock(712, 12);
                            RETURN NEW;
                        END;
                        $$
                        """).update();
        jdbcClient.sql("""
                        CREATE TRIGGER block_plan_creation
                        BEFORE INSERT ON planning_plan FOR EACH ROW
                        EXECUTE FUNCTION test_block_plan_creation()
                        """).update();

        try (var advisoryLockConnection = dataSource.getConnection();
                var advisoryLock = advisoryLockConnection.createStatement();
                var executor = Executors.newFixedThreadPool(2)) {
            advisoryLock.execute("SELECT pg_advisory_lock(712, 12)");
            try {
                var creation = executor.submit(() -> planCreationService.create(command(MEMBER_ID, "creation-before-removal")));
                awaitDatabaseCondition(() -> isAdvisoryLockWaiting());
                var removal = executor.submit(this::removeMember);
                awaitDatabaseCondition(() -> isRemovalWaitingForGroupLock());
                advisoryLock.execute("SELECT pg_advisory_unlock(712, 12)");

                assertThat(creation.get(10, TimeUnit.SECONDS).createdByAccountId()).isEqualTo(MEMBER_ID);
                removal.get(10, TimeUnit.SECONDS);
            } finally {
                advisoryLock.execute("SELECT pg_advisory_unlock(712, 12)");
            }
        }
        assertThat(count("SELECT count(*) FROM planning_plan")).isOne();
        assertThat(groupMemberCount("SELECT count(*) FROM group_membership WHERE group_id = :groupId AND account_id = :memberId AND status = 'REMOVED'")).isOne();
    }

    @Test
    void groupAuthorizationLockBlocksRemovalUntilTheProtectedTransactionCommits() throws Exception {
        var authorizationComplete = new java.util.concurrent.CountDownLatch(1);
        var allowAuthorizationCommit = new java.util.concurrent.CountDownLatch(1);
        var transactionTemplate = new TransactionTemplate(transactionManager);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var authorization = executor.submit(() -> transactionTemplate.execute(status -> {
                assertThat(groupMembershipAccess.lockAndHasActiveMembership(GROUP_ID, MEMBER_ID)).isTrue();
                authorizationComplete.countDown();
                try {
                    if (!allowAuthorizationCommit.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("authorization transaction was not released");
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("authorization transaction was interrupted", exception);
                }
                return null;
            }));
            assertThat(authorizationComplete.await(10, TimeUnit.SECONDS)).isTrue();
            var removal = executor.submit(this::removeMember);
            assertThat(removal.isDone()).isFalse();
            allowAuthorizationCommit.countDown();
            authorization.get(10, TimeUnit.SECONDS);
            removal.get(10, TimeUnit.SECONDS);
        }
        assertThat(groupMemberCount("SELECT count(*) FROM group_membership WHERE group_id = :groupId AND account_id = :memberId AND status = 'REMOVED'")).isOne();
    }

    @Test
    void removalCommittedBeforeCreationRejectsWithoutPlanEffects() {
        removeMember();

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> planCreationService.create(command(MEMBER_ID, "removed-member-plan")))
                .isInstanceOf(com.builtbyjuls.arat.planning.api.PlanCreationException.class);

        assertThat(count("SELECT count(*) FROM planning_plan")).isZero();
        assertThat(count("SELECT count(*) FROM audit_event WHERE action = 'plan.created'")).isZero();
    }

    @Test
    void publishesPlanCreationInExecutableOpenApi() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/v1/groups/{groupId}/plans'].post.operationId").value("createCollaborativePlan"))
                .andExpect(jsonPath("$.paths['/api/v1/groups/{groupId}/plans'].post.responses['201'].headers.ETag").exists())
                .andExpect(jsonPath("$.paths['/api/v1/groups/{groupId}/plans'].post.responses['201'].headers.Location").exists())
                .andExpect(jsonPath("$.paths['/api/v1/groups/{groupId}/plans'].post.responses['404']").exists())
                .andExpect(jsonPath("$.paths['/api/v1/groups/{groupId}/plans'].post.responses['422']").exists())
                .andExpect(jsonPath("$.components.schemas.CreatePlanRequest.properties.validTimeZone").doesNotExist())
                .andExpect(jsonPath("$.components.schemas.CreatePlanRequest.properties.uniqueMustHaves").doesNotExist())
                .andExpect(jsonPath("$.components.schemas.CreatePlanRequest.properties.validCategoryAttributes").doesNotExist())
                .andExpect(jsonPath("$.components.schemas.CreatePlanRequest.properties.categoryAttributes.additionalProperties.oneOf").isArray())
                .andExpect(jsonPath("$.components.schemas.CreatePlanRequest.properties.categoryAttributes.additionalProperties.type").doesNotExist())
                .andExpect(jsonPath("$.components.schemas.CreatePlanRequest.properties.categoryAttributes.additionalProperties.oneOf[1].minimum").value(0))
                .andExpect(jsonPath("$.components.schemas.CreatePlanRequest.properties.categoryAttributes.additionalProperties.oneOf[1].maximum").value(1000000))
                .andExpect(jsonPath("$.components.schemas.CreatePlanRequest.properties.categoryAttributes.additionalProperties.oneOf[2].minLength").value(1))
                .andExpect(jsonPath("$.components.schemas.CreatePlanRequest.properties.categoryAttributes.additionalProperties.oneOf[2].maxLength").value(120));
    }

    private org.springframework.test.web.servlet.ResultActions create(String key, String request) throws Exception {
        return mockMvc.perform(post("/api/v1/groups/{groupId}/plans", GROUP_ID)
                .header("Idempotency-Key", key)
                .header(CorrelationIdFilter.HEADER_NAME, CORRELATION_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content(request));
    }

    private com.builtbyjuls.arat.planning.api.PlanRepresentation createAfterBarrier(
            CreatePlanCommand command, CyclicBarrier barrier) throws Exception {
        barrier.await(10, TimeUnit.SECONDS);
        return planCreationService.create(command);
    }

    private String removeAfterBarrier(CyclicBarrier barrier) throws Exception {
        barrier.await(10, TimeUnit.SECONDS);
        return removeMember();
    }

    private String removeMember() {
        return membershipExitService.remove(new RemoveGroupMemberCommand(
                ORGANIZER_ID, GROUP_ID, MEMBER_ID, "remove-member-race", CORRELATION_ID));
    }

    private CreatePlanCommand command(UUID actorId, String key) throws Exception {
        var attributes = new ObjectMapper().readTree("{\"hasParking\":true,\"courtCount\":2}");
        return new CreatePlanCommand(
                actorId,
                GROUP_ID,
                key,
                new CreatePlanRequest(
                        "Friday badminton",
                        com.builtbyjuls.arat.planning.domain.ActivityCategory.COURT,
                        "Asia/Manila",
                        List.of(new CreatePlanRequest.CandidateWindowRequest(
                                OffsetDateTime.parse("2027-01-09T09:00:00Z"), OffsetDateTime.parse("2027-01-09T11:00:00Z"))),
                        new CreatePlanRequest.AreaRequest("BGC", 5),
                        new CreatePlanRequest.HeadcountRequest(4, 10),
                        new CreatePlanRequest.BudgetRequest("PHP", "0.00", "2500.00"),
                        List.of("parking", "shower"),
                        "Indoor court preferred.",
                        Map.of("hasParking", attributes.path("hasParking"), "courtCount", attributes.path("courtCount"))),
                CORRELATION_ID);
    }

    private String validRequest() {
        return """
                {"title":"Friday badminton","category":"COURT","timeZone":"Asia/Manila",
                "candidateWindows":[{"startAt":"2027-01-09T09:00:00Z","endAt":"2027-01-09T11:00:00Z"}],
                "area":{"code":"BGC","radiusKm":5},"headcount":{"minimum":4,"maximum":10},
                "budget":{"currency":"PHP","minimumAmount":"0.00","maximumAmount":"2500.00"},
                "mustHaves":["parking","shower"],"providerSafeNotes":"Indoor court preferred.",
                "categoryAttributes":{"hasParking":true,"courtCount":2}}
                """.replaceAll("\\R", "");
    }

    private String validRequestWithAttributes(String attributes) {
        return validRequest().replace("{\"hasParking\":true,\"courtCount\":2}", attributes);
    }

    private String attributesWith21Entries() {
        return "{" + java.util.stream.IntStream.range(0, 21)
                .mapToObj(index -> "\"value" + index + "\":true")
                .collect(java.util.stream.Collectors.joining(",")) + "}";
    }

    private String candidateWindowsWith11Entries() {
        return "\"candidateWindows\":[" + java.util.stream.IntStream.range(0, 11)
                .mapToObj(index -> "{\"startAt\":\"2027-01-09T09:00:00Z\",\"endAt\":\"2027-01-09T11:00:00Z\"}")
                .collect(java.util.stream.Collectors.joining(",")) + "]";
    }

    private String mustHavesWith21Entries() {
        return "\"mustHaves\":[" + java.util.stream.IntStream.range(0, 21)
                .mapToObj(index -> "\"mustHave" + index + "\"")
                .collect(java.util.stream.Collectors.joining(",")) + "]";
    }

    private long count(String sql) {
        return jdbcClient.sql(sql).query(Long.class).single();
    }

    private long count(String sql, UUID planId) {
        return jdbcClient.sql(sql).param("planId", planId).query(Long.class).single();
    }

    private long groupMemberCount(String sql) {
        return jdbcClient.sql(sql)
                .param("groupId", GROUP_ID)
                .param("memberId", MEMBER_ID)
                .query(Long.class)
                .single();
    }

    private boolean isAdvisoryLockWaiting() {
        return jdbcClient.sql("""
                        SELECT EXISTS (
                            SELECT 1
                            FROM pg_locks
                            WHERE locktype = 'advisory'
                              AND classid = 712
                              AND objid = 12
                              AND NOT granted
                        )
                        """)
                .query(Boolean.class)
                .single();
    }

    private boolean isRemovalWaitingForGroupLock() {
        return jdbcClient.sql("""
                        SELECT EXISTS (
                            SELECT 1
                            FROM pg_stat_activity
                            WHERE wait_event_type = 'Lock'
                              AND query LIKE '%FROM group_account%FOR UPDATE%'
                        )
                        """)
                .query(Boolean.class)
                .single();
    }

    private void awaitDatabaseCondition(BooleanSupplier condition) throws Exception {
        var deadline = java.time.Instant.now().plus(Duration.ofSeconds(10));
        while (java.time.Instant.now().isBefore(deadline)) {
            if (condition.getAsBoolean()) {
                return;
            }
            java.util.concurrent.locks.LockSupport.parkNanos(Duration.ofMillis(10).toNanos());
        }
        throw new AssertionError("database condition was not reached before timeout");
    }
}

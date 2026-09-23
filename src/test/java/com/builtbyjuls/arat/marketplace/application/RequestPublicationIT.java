package com.builtbyjuls.arat.marketplace.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.builtbyjuls.arat.PostgreSqlIntegrationTest;
import com.builtbyjuls.arat.groups.api.GroupMembershipAccess;
import com.builtbyjuls.arat.identity.api.AuthenticatedActor;
import com.builtbyjuls.arat.marketplace.api.PublishRequestCommand;
import com.builtbyjuls.arat.matching.api.MatchRequestRecipientsCommand;
import com.builtbyjuls.arat.matching.api.MatchingAccess;
import com.builtbyjuls.arat.messaging.api.AppendOutboxEventCommand;
import com.builtbyjuls.arat.messaging.api.MessagingAccess;
import com.builtbyjuls.arat.planning.api.CloseRequestVersionCommand;
import com.builtbyjuls.arat.planning.api.PlanningRequestAccess;
import com.builtbyjuls.arat.planning.api.PublishRequestVersionCommand;
import com.builtbyjuls.arat.web.CorrelationIdFilter;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(properties = "arat.matching.recipient-cap=2")
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class RequestPublicationIT extends PostgreSqlIntegrationTest {

    @Autowired private WebApplicationContext context;
    @Autowired private CorrelationIdFilter correlationIdFilter;
    @Autowired private JdbcClient jdbcClient;
    @Autowired private DataSource dataSource;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private RequestPublicationService publicationService;

    @MockitoSpyBean private GroupMembershipAccess groupMembershipAccess;
    @MockitoSpyBean private PlanningRequestAccess planningRequestAccess;
    @MockitoSpyBean private MatchingAccess matchingAccess;
    @MockitoSpyBean private MessagingAccess messagingAccess;

    private MockMvc mockMvc;
    private TransactionTemplate transactionTemplate;

    @BeforeEach
    void prepareClient() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(correlationIdFilter)
                .apply(springSecurity())
                .build();
        transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Test
    void publishesRequestRecipientsEventsAuditAndReplayAtomically() throws Exception {
        var fixture = fixture(2, false, false);

        var response = publishAs(fixture.organizerId(), fixture.planId(), fixture.finalizationId(), "publish-happy", "\"1\"")
                .andExpect(status().isCreated())
                .andExpect(header().string("ETag", "\"2\""))
                .andExpect(header().string("Location", org.hamcrest.Matchers.matchesPattern(
                        "/api/v1/plans/" + fixture.planId() + "/published-requests/[0-9a-f-]{36}")))
                .andExpect(jsonPath("$.requestVersion").value(1))
                .andExpect(jsonPath("$.state").value("OPEN"))
                .andExpect(jsonPath("$.actionable").value(true))
                .andExpect(jsonPath("$.category").value("COURT"))
                .andExpect(jsonPath("$.area.code").value(fixture.areaCode()))
                .andExpect(jsonPath("$.budget.currency").value("PHP"))
                .andExpect(jsonPath("$.budget.minimumAmount").value("1000.00"))
                .andExpect(jsonPath("$.mustHaves[0]").value("parking"))
                .andReturn().getResponse();
        var requestId = UUID.fromString(objectMapper.readTree(response.getContentAsByteArray()).path("requestId").asText());

        assertPlan(fixture, "OPEN_FOR_OFFERS", requestId, 2);
        assertThat(count("SELECT count(*) FROM planning_published_request WHERE plan_id = :planId", "planId", fixture.planId()))
                .isOne();
        assertThat(providerIds(requestId)).containsExactlyElementsOf(fixture.providerIds());
        assertThat(count("SELECT count(*) FROM messaging_outbox WHERE aggregate_id = :requestId", "requestId", requestId))
                .isEqualTo(2);
        assertThat(jdbcClient.sql("""
                        SELECT business_key
                        FROM messaging_outbox
                        WHERE aggregate_id = :requestId
                        ORDER BY business_key
                        """)
                .param("requestId", requestId)
                .query(String.class)
                .list())
                .containsExactlyElementsOf(fixture.providerIds().stream()
                        .map(providerId -> "ProviderRequestPublished:" + requestId + ":" + providerId)
                        .sorted()
                        .toList());
        assertSafeOutboxPayloads(requestId);
        assertThat(count("SELECT count(*) FROM audit_event WHERE action = 'published_request.published' AND subject_id = :requestId",
                "requestId", requestId)).isOne();
        assertThat(count("SELECT count(*) FROM idempotency_record WHERE operation = :operation AND actor_id = :actorId AND state = 'COMPLETED'",
                "operation", RequestPublicationService.PUBLISH_REQUEST_OPERATION, "actorId", fixture.organizerId())).isOne();

        var json = response.getContentAsString();
        assertThat(json).doesNotContain(
                "groupId", "groupName", "planId", "title", "createdByAccountId", "member",
                "preference", "attendance", "privateNote", "employer", "contact");
    }

    @Test
    void replaysMaximumMultibyteSnapshotWithoutPassingAttributesThroughReplayState() throws Exception {
        var fixture = fixture(1, true, false);

        var first = publishAs(fixture.organizerId(), fixture.planId(), fixture.finalizationId(), "publish-large", "\"1\"")
                .andExpect(status().isCreated())
                .andReturn().getResponse();
        var requestId = UUID.fromString(objectMapper.readTree(first.getContentAsByteArray()).path("requestId").asText());
        transactionTemplate.executeWithoutResult(status -> planningRequestAccess.close(
                new CloseRequestVersionCommand(fixture.planId(), requestId, 2)));

        var replay = publishAs(fixture.organizerId(), fixture.planId(), fixture.finalizationId(), "publish-large", "\"1\"")
                .andExpect(status().isCreated())
                .andExpect(header().string("ETag", first.getHeader("ETag")))
                .andExpect(header().string("Location", first.getHeader("Location")))
                .andReturn().getResponse();

        assertThat(first.getContentAsByteArray()).hasSizeGreaterThan(16_384);
        assertThat(replay.getContentAsString()).isEqualTo(first.getContentAsString());
        assertThat(publicationCount(fixture.planId())).isOne();
        assertPlan(fixture, "COLLABORATING", null, 3);
        assertThat(jdbcClient.sql("""
                        SELECT replay_state::text
                        FROM idempotency_record
                        WHERE operation = :operation AND actor_id = :actorId
                        """)
                .param("operation", RequestPublicationService.PUBLISH_REQUEST_OPERATION)
                .param("actorId", fixture.organizerId())
                .query(String.class)
                .single()).isEqualTo("{}");
    }

    @Test
    void concurrentExactReplayReturnsOnePublication() throws Exception {
        var fixture = fixture(1, false, false);
        var command = command(fixture, "publish-concurrent", 1);
        var barrier = new CyclicBarrier(2);

        try (var blocker = dataSource.getConnection()) {
            blocker.setAutoCommit(false);
            try (var statement = blocker.prepareStatement(
                    "SELECT group_id FROM group_account WHERE group_id = ? FOR UPDATE")) {
                statement.setObject(1, fixture.groupId());
                statement.executeQuery();
            }
            try (var executor = Executors.newFixedThreadPool(2)) {
                var first = executor.submit(() -> publishAfterBarrier(command, barrier));
                var second = executor.submit(() -> publishAfterBarrier(command, barrier));
                try {
                    awaitBlockedTransactions(2);
                } finally {
                    blocker.commit();
                }
                var firstResponse = first.get(10, TimeUnit.SECONDS);
                var secondResponse = second.get(10, TimeUnit.SECONDS);
                assertThat(firstResponse.request().requestId()).isEqualTo(secondResponse.request().requestId());
                assertThat(firstResponse.location()).isEqualTo(secondResponse.location());
            }
        }

        var requestId = requestIdForPlan(fixture.planId());
        assertThat(publicationCount(fixture.planId())).isOne();
        assertThat(providerIds(requestId)).containsExactlyElementsOf(fixture.providerIds());
        assertThat(count("SELECT count(*) FROM messaging_outbox WHERE aggregate_id = :requestId", "requestId", requestId))
                .isOne();
        assertThat(count("SELECT count(*) FROM audit_event WHERE action = 'published_request.published' AND subject_id = :requestId",
                "requestId", requestId)).isOne();
    }

    @Test
    void changedFinalizationPlanOrExpectedVersionUnderCompletedKeyConflicts() throws Exception {
        var fixture = fixture(1, false, false);
        publishAs(fixture.organizerId(), fixture.planId(), fixture.finalizationId(), "publish-fingerprint", "\"1\"")
                .andExpect(status().isCreated());

        publishAs(fixture.organizerId(), fixture.planId(), UUID.randomUUID(), "publish-fingerprint", "\"1\"")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
        publishAs(fixture.organizerId(), fixture.planId(), fixture.finalizationId(), "publish-fingerprint", "\"2\"")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
        publishAs(fixture.organizerId(), UUID.randomUUID(), fixture.finalizationId(), "publish-fingerprint", "\"1\"")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));

        assertThat(publicationCount(fixture.planId())).isOne();
    }

    @Test
    void rejectsUnauthorizedStaleForeignAndElapsedPublicationWithoutWrites() throws Exception {
        var fixture = fixture(1, false, false);
        var foreign = fixture(1, false, false);

        publishAs(fixture.memberId(), fixture.planId(), fixture.finalizationId(), "publish-member", "\"1\"")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN_ROLE"));
        publishAs(fixture.outsiderId(), fixture.planId(), fixture.finalizationId(), "publish-outsider", "\"1\"")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PRIVATE_RESOURCE_NOT_FOUND"));
        publishAs(fixture.organizerId(), fixture.planId(), fixture.finalizationId(), "publish-stale-etag", "\"2\"")
                .andExpect(status().isPreconditionFailed())
                .andExpect(jsonPath("$.code").value("PRECONDITION_FAILED"));
        publishAs(fixture.organizerId(), fixture.planId(), foreign.finalizationId(), "publish-foreign", "\"1\"")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PRIVATE_RESOURCE_NOT_FOUND"));

        var stale = fixture(1, false, false);
        jdbcClient.sql("UPDATE planning_plan SET version = 2 WHERE plan_id = :planId")
                .param("planId", stale.planId())
                .update();
        publishAs(stale.organizerId(), stale.planId(), stale.finalizationId(), "publish-stale-finalization", "\"2\"")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("FINALIZATION_VERSION_CHANGED"));

        var elapsed = fixture(1, false, true);
        publishAs(elapsed.organizerId(), elapsed.planId(), elapsed.finalizationId(), "publish-elapsed", "\"1\"")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REQUEST_DEADLINE_EXPIRED"));

        assertNoPublicationWrites(fixture);
        assertNoPublicationWrites(stale);
        assertNoPublicationWrites(elapsed);
    }

    @Test
    void rejectsZeroAndOverflowAudienceWithoutTruncation() throws Exception {
        var zero = fixture(0, false, false);
        publishAs(zero.organizerId(), zero.planId(), zero.finalizationId(), "publish-zero", "\"1\"")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("NO_ELIGIBLE_PROVIDERS"));
        assertNoPublicationWrites(zero);

        var overflow = fixture(3, false, false);
        publishAs(overflow.organizerId(), overflow.planId(), overflow.finalizationId(), "publish-overflow", "\"1\"")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RECIPIENT_LIMIT_EXCEEDED"));
        assertNoPublicationWrites(overflow);
    }

    @Test
    void acceptedWhitespaceAreaCreatesFinalizesAndReturnsExactMatchNoCandidates() throws Exception {
        var fixture = fixture(0, false, false);
        var startAt = OffsetDateTime.now(ZoneOffset.UTC)
                .plusDays(3)
                .truncatedTo(ChronoUnit.MICROS);
        var created = mockMvc.perform(post("/api/v1/groups/{groupId}/plans", fixture.groupId())
                        .with(actor(fixture.organizerId()))
                        .header("Idempotency-Key", "publish-whitespace-create")
                        .header(CorrelationIdFilter.HEADER_NAME, "publication-test")
                        .contentType("application/json")
                        .content("""
                                {"title":"Whitespace area plan","category":"COURT","timeZone":"Asia/Manila",
                                "candidateWindows":[{"startAt":"%s","endAt":"%s"}],
                                "area":{"code":" BGC ","radiusKm":5},
                                "headcount":{"minimum":4,"maximum":10},"mustHaves":[],
                                "categoryAttributes":{}}
                                """.formatted(startAt, startAt.plusHours(2)).replaceAll("\\R", "")))
                .andExpect(status().isCreated())
                .andExpect(header().string("ETag", "\"1\""))
                .andReturn().getResponse();
        var planId = UUID.fromString(objectMapper.readTree(created.getContentAsByteArray()).path("planId").asText());
        assertThat(jdbcClient.sql("SELECT area_code FROM planning_requirement_draft WHERE plan_id = :planId")
                .param("planId", planId)
                .query(String.class)
                .single()).isEqualTo(" BGC ");
        var windowId = jdbcClient.sql("""
                        SELECT candidate_window_id
                        FROM planning_candidate_window
                        WHERE plan_id = :planId
                        """)
                .param("planId", planId)
                .query(UUID.class)
                .single();

        var finalized = mockMvc.perform(post("/api/v1/plans/{planId}/requirement-finalization", planId)
                        .with(actor(fixture.organizerId()))
                        .header("Idempotency-Key", "publish-whitespace-finalize")
                        .header("If-Match", "\"1\"")
                        .header(CorrelationIdFilter.HEADER_NAME, "publication-test")
                        .contentType("application/json")
                        .content("{\"candidateWindowId\":\"" + windowId
                                + "\",\"offerDeadline\":\"" + startAt.minusHours(1) + "\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.area.code").value(" BGC "))
                .andReturn().getResponse();
        var finalizationId = UUID.fromString(
                objectMapper.readTree(finalized.getContentAsByteArray()).path("finalizationId").asText());

        publishAs(fixture.organizerId(), planId, finalizationId, "publish-whitespace", "\"1\"")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("NO_ELIGIBLE_PROVIDERS"));

        assertNoPublicationWrites(new Fixture(
                fixture.organizerId(), fixture.memberId(), fixture.outsiderId(), fixture.groupId(),
                planId, windowId, finalizationId, " BGC ", startAt, List.of()));
    }

    @Test
    void reopensWithTheNextHistoricalRequestVersion() throws Exception {
        var fixture = fixture(1, false, false);
        var first = publicationService.publish(command(fixture, "publish-first", 1));

        transactionTemplate.executeWithoutResult(status -> planningRequestAccess.close(
                new CloseRequestVersionCommand(fixture.planId(), first.request().requestId(), 2)));
        var secondFinalizationId = insertFinalization(fixture, 3, false);

        publishAs(fixture.organizerId(), fixture.planId(), secondFinalizationId, "publish-second", "\"3\"")
                .andExpect(status().isCreated())
                .andExpect(header().string("ETag", "\"4\""))
                .andExpect(jsonPath("$.requestVersion").value(2));

        assertThat(jdbcClient.sql("""
                        SELECT request_version
                        FROM planning_published_request
                        WHERE plan_id = :planId
                        ORDER BY request_version
                        """)
                .param("planId", fixture.planId())
                .query(Long.class)
                .list()).containsExactly(1L, 2L);
    }

    @Test
    void rollsBackWhenGroupsBoundaryFails() throws Exception {
        var fixture = fixture(1, false, false);
        doThrow(new IllegalStateException("forced groups boundary failure"))
                .doCallRealMethod()
                .when(AopTestUtils.<GroupMembershipAccess>getTargetObject(groupMembershipAccess))
                .lockGroup(eq(fixture.groupId()));

        publishAs(fixture.organizerId(), fixture.planId(), fixture.finalizationId(), "publish-groups-failure", "\"1\"")
                .andExpect(status().isInternalServerError());
        assertNoPublicationWrites(fixture);
    }

    @Test
    void rollsBackWhenPlanningBoundaryFails() throws Exception {
        var fixture = fixture(1, false, false);
        doThrow(new IllegalStateException("forced planning boundary failure"))
                .doCallRealMethod()
                .when(AopTestUtils.<PlanningRequestAccess>getTargetObject(planningRequestAccess))
                .create(any(PublishRequestVersionCommand.class));

        publishAs(fixture.organizerId(), fixture.planId(), fixture.finalizationId(), "publish-planning-failure", "\"1\"")
                .andExpect(status().isInternalServerError());
        assertNoPublicationWrites(fixture);
    }

    @Test
    void rollsBackWhenMatchingBoundaryFails() throws Exception {
        var fixture = fixture(1, false, false);
        doThrow(new IllegalStateException("forced matching boundary failure"))
                .doCallRealMethod()
                .when(AopTestUtils.<MatchingAccess>getTargetObject(matchingAccess))
                .selectRecipients(any(MatchRequestRecipientsCommand.class));

        publishAs(fixture.organizerId(), fixture.planId(), fixture.finalizationId(), "publish-matching-failure", "\"1\"")
                .andExpect(status().isInternalServerError());
        assertNoPublicationWrites(fixture);
    }

    @Test
    void rollsBackWhenMessagingBoundaryFails() throws Exception {
        var fixture = fixture(1, false, false);
        doThrow(new IllegalStateException("forced messaging boundary failure"))
                .doCallRealMethod()
                .when(AopTestUtils.<MessagingAccess>getTargetObject(messagingAccess))
                .append(any(AppendOutboxEventCommand.class));

        publishAs(fixture.organizerId(), fixture.planId(), fixture.finalizationId(), "publish-messaging-failure", "\"1\"")
                .andExpect(status().isInternalServerError());
        assertNoPublicationWrites(fixture);
    }

    @Test
    void publishesExecutableOpenApi() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/v1/plans/{planId}/published-requests'].post.operationId")
                        .value("publishProviderRequest"))
                .andExpect(jsonPath("$.paths['/api/v1/plans/{planId}/published-requests'].post.parameters[?(@.name == 'Idempotency-Key')].required")
                        .value(true))
                .andExpect(jsonPath("$.paths['/api/v1/plans/{planId}/published-requests'].post.responses['201'].headers.ETag").exists())
                .andExpect(jsonPath("$.paths['/api/v1/plans/{planId}/published-requests'].post.responses['201'].headers.Location").exists());
    }

    private ResultActions publishAs(
            UUID actorId,
            UUID planId,
            UUID finalizationId,
            String idempotencyKey,
            String etag) throws Exception {
        var request = post("/api/v1/plans/{planId}/published-requests", planId)
                .with(actor(actorId))
                .header("Idempotency-Key", idempotencyKey)
                .header(CorrelationIdFilter.HEADER_NAME, "publication-test")
                .contentType("application/json")
                .content("{\"finalizationId\":\"" + finalizationId + "\"}");
        if (etag != null) {
            request.header("If-Match", etag);
        }
        return mockMvc.perform(request);
    }

    private Fixture fixture(int providerCount, boolean maximumContent, boolean elapsedDeadline) throws Exception {
        var organizerId = UUID.randomUUID();
        var memberId = UUID.randomUUID();
        var outsiderId = UUID.randomUUID();
        var groupId = UUID.randomUUID();
        var planId = UUID.randomUUID();
        var windowId = UUID.randomUUID();
        var areaCode = "AREA-" + planId.toString().substring(0, 8);
        var startAt = OffsetDateTime.now(ZoneOffset.UTC)
                .plusDays(3)
                .truncatedTo(ChronoUnit.MICROS);
        var note = maximumContent ? "\u4e00".repeat(1000) : "Indoor court preferred.";
        var attributes = maximumContent ? maximumAttributes() : java.util.Map.of("courtCount", 2);

        jdbcClient.sql("""
                        INSERT INTO identity_account (account_id, display_name)
                        VALUES (:organizerId, 'Publication organizer'),
                               (:memberId, 'Publication member'),
                               (:outsiderId, 'Publication outsider')
                        """)
                .param("organizerId", organizerId)
                .param("memberId", memberId)
                .param("outsiderId", outsiderId)
                .update();
        jdbcClient.sql("""
                        INSERT INTO group_account (
                            group_id, name, description, status, created_by_account_id
                        )
                        VALUES (:groupId, 'Publication group', '', 'ACTIVE', :organizerId)
                        """)
                .param("groupId", groupId)
                .param("organizerId", organizerId)
                .update();
        jdbcClient.sql("""
                        INSERT INTO group_membership (group_id, account_id, role, status, joined_at)
                        VALUES (:groupId, :organizerId, 'ORGANIZER', 'ACTIVE', statement_timestamp()),
                               (:groupId, :memberId, 'MEMBER', 'ACTIVE', statement_timestamp())
                        """)
                .param("groupId", groupId)
                .param("organizerId", organizerId)
                .param("memberId", memberId)
                .update();
        jdbcClient.sql("""
                        INSERT INTO planning_plan (
                            plan_id, group_id, title, state, created_by_account_id, version
                        )
                        VALUES (:planId, :groupId, 'Private publication plan', 'COLLABORATING', :organizerId, 1)
                        """)
                .param("planId", planId)
                .param("groupId", groupId)
                .param("organizerId", organizerId)
                .update();
        jdbcClient.sql("""
                        INSERT INTO planning_requirement_draft (
                            plan_id, category, time_zone, area_code, radius_km,
                            minimum_headcount, maximum_headcount, budget_currency,
                            budget_minimum_minor_units, budget_maximum_minor_units,
                            provider_safe_notes, category_attributes
                        )
                        VALUES (
                            :planId, 'COURT', 'Asia/Manila', :areaCode, 5,
                            4, 10, 'PHP', 100000, 250000,
                            :providerSafeNotes, CAST(:categoryAttributes AS jsonb)
                        )
                        """)
                .param("planId", planId)
                .param("areaCode", areaCode)
                .param("providerSafeNotes", note)
                .param("categoryAttributes", objectMapper.writeValueAsString(attributes))
                .update();
        jdbcClient.sql("""
                        INSERT INTO planning_candidate_window (
                            candidate_window_id, plan_id, sort_order, starts_at, ends_at
                        )
                        VALUES (:windowId, :planId, 1, :startAt, :endAt)
                        """)
                .param("windowId", windowId)
                .param("planId", planId)
                .param("startAt", startAt)
                .param("endAt", startAt.plusHours(2))
                .update();
        var mustHaves = maximumContent ? maximumMustHaves() : List.of("parking", "shower");
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

        var providerIds = new ArrayList<UUID>();
        for (var index = 0; index < providerCount; index++) {
            var providerId = UUID.randomUUID();
            providerIds.add(providerId);
            jdbcClient.sql("""
                            INSERT INTO provider_organization (
                                provider_id, display_name, status, verification_status,
                                version, eligibility_version
                            )
                            VALUES (:providerId, :displayName, 'ACTIVE', 'VERIFIED', 2, 2)
                            """)
                    .param("providerId", providerId)
                    .param("displayName", "Publication provider " + index)
                    .update();
            jdbcClient.sql("""
                            INSERT INTO provider_supported_category (provider_id, category)
                            VALUES (:providerId, 'COURT')
                            """)
                    .param("providerId", providerId)
                    .update();
            jdbcClient.sql("""
                            INSERT INTO provider_service_area (provider_id, area_code)
                            VALUES (:providerId, :areaCode)
                            """)
                    .param("providerId", providerId)
                    .param("areaCode", areaCode)
                    .update();
        }
        providerIds.sort(java.util.Comparator.comparing(UUID::toString));
        var fixture = new Fixture(
                organizerId, memberId, outsiderId, groupId, planId, windowId, null, areaCode,
                startAt, List.copyOf(providerIds));
        var finalizationId = insertFinalization(fixture, 1, elapsedDeadline);
        return new Fixture(
                organizerId, memberId, outsiderId, groupId, planId, windowId, finalizationId, areaCode,
                startAt, List.copyOf(providerIds));
    }

    private UUID insertFinalization(Fixture fixture, long basisPlanVersion, boolean elapsedDeadline) {
        var finalizationId = UUID.randomUUID();
        var deadline = elapsedDeadline
                ? OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(1).truncatedTo(ChronoUnit.MICROS)
                : fixture.startAt().minusHours(1);
        jdbcClient.sql("""
                        INSERT INTO planning_requirement_finalization (
                            finalization_id, plan_id, basis_plan_version,
                            selected_candidate_window_id, selected_starts_at, selected_ends_at,
                            offer_deadline, category, time_zone, area_code, radius_km,
                            minimum_headcount, maximum_headcount, budget_currency,
                            budget_minimum_minor_units, budget_maximum_minor_units, must_haves,
                            provider_safe_notes, category_attributes, current_preference_count,
                            stale_preference_count, warnings, finalized_by_account_id, created_at
                        )
                        SELECT
                            :finalizationId, p.plan_id, :basisPlanVersion,
                            w.candidate_window_id, w.starts_at, w.ends_at,
                            :offerDeadline, d.category, d.time_zone, d.area_code, d.radius_km,
                            d.minimum_headcount, d.maximum_headcount, d.budget_currency,
                            d.budget_minimum_minor_units, d.budget_maximum_minor_units,
                            ARRAY(
                                SELECT m.must_have
                                FROM planning_requirement_must_have m
                                WHERE m.plan_id = p.plan_id
                                ORDER BY m.sort_order
                            )::varchar[],
                            d.provider_safe_notes, d.category_attributes, 0,
                            0, ARRAY['NO_CURRENT_PREFERENCE_INPUT']::varchar[],
                            :organizerId, statement_timestamp()
                        FROM planning_plan p
                        JOIN planning_requirement_draft d ON d.plan_id = p.plan_id
                        JOIN planning_candidate_window w
                          ON w.plan_id = p.plan_id AND w.candidate_window_id = :windowId
                        WHERE p.plan_id = :planId
                        """)
                .param("finalizationId", finalizationId)
                .param("basisPlanVersion", basisPlanVersion)
                .param("offerDeadline", deadline)
                .param("organizerId", fixture.organizerId())
                .param("windowId", fixture.windowId())
                .param("planId", fixture.planId())
                .update();
        return finalizationId;
    }

    private LinkedHashMap<String, Object> maximumAttributes() {
        var value = "\u4e00".repeat(120);
        var attributes = new LinkedHashMap<String, Object>();
        attributes.put("tokenValue", value);
        attributes.put("secretValue", value);
        attributes.put("authorizationValue", value);
        attributes.put("passwordValue", value);
        attributes.put("cookieValue", value);
        for (var index = 1; index <= 15; index++) {
            attributes.put("attribute" + index, value);
        }
        return attributes;
    }

    private List<String> maximumMustHaves() {
        return java.util.stream.IntStream.rangeClosed(1, 20)
                .mapToObj(index -> "\u4e00".repeat(118) + String.format("%02d", index))
                .toList();
    }

    private PublishRequestCommand command(Fixture fixture, String key, long expectedVersion) {
        return new PublishRequestCommand(
                fixture.organizerId(), fixture.planId(), fixture.finalizationId(),
                expectedVersion, key, "publication-test");
    }

    private com.builtbyjuls.arat.marketplace.api.RequestPublicationResponse publishAfterBarrier(
            PublishRequestCommand command, CyclicBarrier barrier) throws Exception {
        barrier.await(10, TimeUnit.SECONDS);
        return publicationService.publish(command);
    }

    private void awaitBlockedTransactions(int expected) {
        var expiresAt = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < expiresAt) {
            var blocked = jdbcClient.sql("""
                            SELECT count(*)
                            FROM pg_stat_activity
                            WHERE datname = current_database()
                              AND cardinality(pg_blocking_pids(pid)) > 0
                            """)
                    .query(Integer.class)
                    .single();
            if (blocked >= expected) {
                return;
            }
            Thread.onSpinWait();
        }
        throw new AssertionError("Timed out waiting for " + expected + " blocked transactions");
    }

    private RequestPostProcessor actor(UUID actorId) {
        return authentication(new TestingAuthenticationToken(
                new AuthenticatedActor(actorId, Set.of()), null, "ROLE_USER"));
    }

    private void assertPlan(Fixture fixture, String state, UUID currentRequestId, long version) {
        var row = jdbcClient.sql("""
                        SELECT state, current_request_id, version
                        FROM planning_plan
                        WHERE plan_id = :planId
                        """)
                .param("planId", fixture.planId())
                .query((resultSet, rowNum) -> new PlanRow(
                        resultSet.getString("state"),
                        resultSet.getObject("current_request_id", UUID.class),
                        resultSet.getLong("version")))
                .single();
        assertThat(row.state()).isEqualTo(state);
        assertThat(row.currentRequestId()).isEqualTo(currentRequestId);
        assertThat(row.version()).isEqualTo(version);
    }

    private void assertNoPublicationWrites(Fixture fixture) {
        assertPlan(fixture, "COLLABORATING", null, currentPlanVersion(fixture.planId()));
        assertThat(publicationCount(fixture.planId())).isZero();
        assertThat(count("SELECT count(*) FROM audit_event WHERE action = 'published_request.published' AND plan_id = :planId",
                "planId", fixture.planId())).isZero();
        assertThat(count("SELECT count(*) FROM idempotency_record WHERE operation = :operation AND actor_id = :actorId",
                "operation", RequestPublicationService.PUBLISH_REQUEST_OPERATION, "actorId", fixture.organizerId())).isZero();
    }

    private long currentPlanVersion(UUID planId) {
        return jdbcClient.sql("SELECT version FROM planning_plan WHERE plan_id = :planId")
                .param("planId", planId)
                .query(Long.class)
                .single();
    }

    private long publicationCount(UUID planId) {
        return count("SELECT count(*) FROM planning_published_request WHERE plan_id = :planId", "planId", planId);
    }

    private UUID requestIdForPlan(UUID planId) {
        return jdbcClient.sql("SELECT request_id FROM planning_published_request WHERE plan_id = :planId")
                .param("planId", planId)
                .query(UUID.class)
                .single();
    }

    private List<UUID> providerIds(UUID requestId) {
        return jdbcClient.sql("""
                        SELECT provider_id
                        FROM marketplace_request_recipient
                        WHERE published_request_id = :requestId
                        ORDER BY provider_id::text
                        """)
                .param("requestId", requestId)
                .query(UUID.class)
                .list();
    }

    private void assertSafeOutboxPayloads(UUID requestId) {
        var payloads = jdbcClient.sql("""
                        SELECT payload::text
                        FROM messaging_outbox
                        WHERE aggregate_id = :requestId
                        """)
                .param("requestId", requestId)
                .query(String.class)
                .list();
        assertThat(payloads).allSatisfy(payload -> {
            assertThat(payload).contains("requestId", "providerId");
            assertThat(payload.toLowerCase()).doesNotContain(
                    "group", "member", "employer", "note", "contact", "title", "preference", "attendance");
        });
    }

    private long count(String sql, Object... parameters) {
        var statement = jdbcClient.sql(sql);
        for (var index = 0; index < parameters.length; index += 2) {
            statement = statement.param((String) parameters[index], parameters[index + 1]);
        }
        return statement.query(Long.class).single();
    }

    private record Fixture(
            UUID organizerId,
            UUID memberId,
            UUID outsiderId,
            UUID groupId,
            UUID planId,
            UUID windowId,
            UUID finalizationId,
            String areaCode,
            OffsetDateTime startAt,
            List<UUID> providerIds) {
    }

    private record PlanRow(String state, UUID currentRequestId, long version) {
    }
}

package com.builtbyjuls.arat.marketplace.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.builtbyjuls.arat.PostgreSqlIntegrationTest;
import com.builtbyjuls.arat.identity.api.AuthenticatedActor;
import com.builtbyjuls.arat.marketplace.api.ClosePublishedRequestCommand;
import com.builtbyjuls.arat.marketplace.api.RequestClosureException;
import com.builtbyjuls.arat.platform.audit.AuditEvent;
import com.builtbyjuls.arat.platform.audit.AuditEventWriter;
import com.builtbyjuls.arat.web.CorrelationIdFilter;
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

@SpringBootTest
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class RequestClosureIT extends PostgreSqlIntegrationTest {

    @Autowired private WebApplicationContext context;
    @Autowired private CorrelationIdFilter correlationIdFilter;
    @Autowired private JdbcClient jdbcClient;
    @Autowired private DataSource dataSource;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private RequestClosureService closureService;

    @MockitoSpyBean private AuditEventWriter auditEventWriter;

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
    void closesCurrentRequestAndCommitsFixedAudienceEventsAuditAndReplay() throws Exception {
        var fixture = fixture();

        var response = closeAs(fixture.organizerId(), fixture.currentRequestId(), "close-happy", "\"2\"")
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"3\""))
                .andExpect(jsonPath("$.requestId").value(fixture.currentRequestId().toString()))
                .andExpect(jsonPath("$.requestVersion").value(2))
                .andExpect(jsonPath("$.state").value("CLOSED"))
                .andExpect(jsonPath("$.actionable").value(false))
                .andExpect(jsonPath("$.category").value("COURT"))
                .andExpect(jsonPath("$.area.code").value("BGC"))
                .andReturn().getResponse();

        assertPlan(fixture, "COLLABORATING", null, 3);
        assertRequestState(fixture.currentRequestId(), "CLOSED", true);
        assertThat(recipientIds(fixture.currentRequestId())).containsExactlyElementsOf(fixture.providerIds());
        assertThat(closedBusinessKeys(fixture.currentRequestId())).containsExactlyElementsOf(
                fixture.providerIds().stream()
                        .map(providerId -> "ProviderRequestClosed:" + fixture.currentRequestId() + ":" + providerId)
                        .sorted()
                        .toList());
        assertSafeClosurePayloads(fixture.currentRequestId());
        assertThat(count("""
                        SELECT count(*) FROM audit_event
                        WHERE action = 'published_request.closed' AND subject_id = :requestId
                        """, "requestId", fixture.currentRequestId())).isOne();
        assertThat(count("""
                        SELECT count(*) FROM idempotency_record
                        WHERE operation = :operation AND actor_id = :actorId AND state = 'COMPLETED'
                        """, "operation", RequestClosureService.CLOSE_REQUEST_OPERATION,
                "actorId", fixture.organizerId())).isOne();

        var replay = closeAs(fixture.organizerId(), fixture.currentRequestId(), "close-happy", "\"2\"")
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"3\""))
                .andReturn().getResponse();
        assertThat(replay.getContentAsString()).isEqualTo(response.getContentAsString());
        assertThat(closedBusinessKeys(fixture.currentRequestId())).hasSize(2);
    }

    @Test
    void enforcesPrivacyPreconditionsCurrentRequestAndOpenState() throws Exception {
        var fixture = fixture();

        closeAs(fixture.organizerId(), fixture.currentRequestId(), "close-missing-etag", null)
                .andExpect(status().isPreconditionRequired())
                .andExpect(jsonPath("$.code").value("PRECONDITION_REQUIRED"));
        closeAs(fixture.organizerId(), fixture.currentRequestId(), "close-bad-etag", "2")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PRECONDITION"));
        closeAs(fixture.memberId(), fixture.currentRequestId(), "close-member", "\"2\"")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN_ROLE"));
        closeAs(fixture.outsiderId(), fixture.currentRequestId(), "close-outsider", "\"2\"")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PRIVATE_RESOURCE_NOT_FOUND"));
        closeAs(fixture.organizerId(), UUID.randomUUID(), "close-unknown", "\"2\"")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PRIVATE_RESOURCE_NOT_FOUND"));
        closeAs(fixture.organizerId(), fixture.currentRequestId(), "close-stale", "\"1\"")
                .andExpect(status().isPreconditionFailed())
                .andExpect(jsonPath("$.code").value("PRECONDITION_FAILED"));
        closeAs(fixture.organizerId(), fixture.historicalRequestId(), "close-noncurrent", "\"2\"")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST_STATE"));

        assertPlan(fixture, "OPEN_FOR_OFFERS", fixture.currentRequestId(), 2);
        assertRequestState(fixture.currentRequestId(), "OPEN", false);

        closeAs(fixture.organizerId(), fixture.currentRequestId(), "close-once", "\"2\"")
                .andExpect(status().isOk());
        closeAs(fixture.organizerId(), fixture.currentRequestId(), "close-again", "\"3\"")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST_STATE"));
        assertPlan(fixture, "COLLABORATING", null, 3);
        assertRequestState(fixture.currentRequestId(), "CLOSED", true);
    }

    @Test
    void changedRequestOrPlanVersionUnderCompletedKeyConflicts() throws Exception {
        var fixture = fixture();
        closeAs(fixture.organizerId(), fixture.currentRequestId(), "close-fingerprint", "\"2\"")
                .andExpect(status().isOk());

        closeAs(fixture.organizerId(), fixture.currentRequestId(), "close-fingerprint", "\"3\"")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
        closeAs(fixture.organizerId(), fixture.historicalRequestId(), "close-fingerprint", "\"2\"")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));

        assertThat(closedBusinessKeys(fixture.currentRequestId())).hasSize(2);
        assertThat(count("""
                        SELECT count(*) FROM audit_event
                        WHERE action = 'published_request.closed' AND subject_id = :requestId
                        """, "requestId", fixture.currentRequestId())).isOne();
    }

    @Test
    void failureAfterTerminalEventAppendRollsBackEveryTransition() throws Exception {
        var fixture = fixture();
        doThrow(new IllegalStateException("forced closure audit failure"))
                .doCallRealMethod()
                .when(AopTestUtils.<AuditEventWriter>getTargetObject(auditEventWriter))
                .append(any(AuditEvent.class));

        closeAs(fixture.organizerId(), fixture.currentRequestId(), "close-rollback", "\"2\"")
                .andExpect(status().isInternalServerError());

        assertPlan(fixture, "OPEN_FOR_OFFERS", fixture.currentRequestId(), 2);
        assertRequestState(fixture.currentRequestId(), "OPEN", false);
        assertThat(closedBusinessKeys(fixture.currentRequestId())).isEmpty();
        assertThat(count("""
                        SELECT count(*) FROM audit_event
                        WHERE action = 'published_request.closed' AND subject_id = :requestId
                        """, "requestId", fixture.currentRequestId())).isZero();
        assertThat(count("""
                        SELECT count(*) FROM idempotency_record
                        WHERE operation = :operation AND actor_id = :actorId
                        """, "operation", RequestClosureService.CLOSE_REQUEST_OPERATION,
                "actorId", fixture.organizerId())).isZero();
    }

    @Test
    void concurrentClosuresWithOnePlanVersionLeaveOneCoherentWinner() throws Exception {
        var fixture = fixture();
        var barrier = new CyclicBarrier(2);
        var firstCommand = command(fixture, "close-concurrent-first", 2);
        var secondCommand = command(fixture, "close-concurrent-second", 2);

        try (var blocker = dataSource.getConnection()) {
            blocker.setAutoCommit(false);
            try (var statement = blocker.prepareStatement(
                    "SELECT group_id FROM group_account WHERE group_id = ? FOR UPDATE")) {
                statement.setObject(1, fixture.groupId());
                statement.executeQuery();
            }
            try (var executor = Executors.newFixedThreadPool(2)) {
                var first = executor.submit(() -> closeAfterBarrier(firstCommand, barrier));
                var second = executor.submit(() -> closeAfterBarrier(secondCommand, barrier));
                try {
                    awaitBlockedTransactions(2);
                } finally {
                    blocker.commit();
                }
                var outcomes = List.of(
                        first.get(10, TimeUnit.SECONDS),
                        second.get(10, TimeUnit.SECONDS));
                assertThat(outcomes).filteredOn(com.builtbyjuls.arat.marketplace.api.RequestClosureResponse.class::isInstance)
                        .hasSize(1);
                assertThat(outcomes).filteredOn(RequestClosureException.class::isInstance)
                        .singleElement()
                        .satisfies(outcome -> assertThat(((RequestClosureException) outcome).reason())
                                .isEqualTo(RequestClosureException.Reason.PRECONDITION_FAILED));
            }
        }

        assertPlan(fixture, "COLLABORATING", null, 3);
        assertRequestState(fixture.currentRequestId(), "CLOSED", true);
        assertThat(closedBusinessKeys(fixture.currentRequestId())).hasSize(2);
        assertThat(count("""
                        SELECT count(*) FROM audit_event
                        WHERE action = 'published_request.closed' AND subject_id = :requestId
                        """, "requestId", fixture.currentRequestId())).isOne();
        assertThat(count("""
                        SELECT count(*) FROM idempotency_record
                        WHERE operation = :operation AND actor_id = :actorId
                        """, "operation", RequestClosureService.CLOSE_REQUEST_OPERATION,
                "actorId", fixture.organizerId())).isOne();
    }

    @Test
    void publishesExecutableOpenApi() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/v1/published-requests/{requestId}/closure'].post.operationId")
                        .value("closeProviderRequest"))
                .andExpect(jsonPath("$.paths['/api/v1/published-requests/{requestId}/closure'].post.parameters[?(@.name == 'Idempotency-Key')].required")
                        .value(true))
                .andExpect(jsonPath("$.paths['/api/v1/published-requests/{requestId}/closure'].post.parameters[?(@.name == 'If-Match')].required")
                        .value(true))
                .andExpect(jsonPath("$.paths['/api/v1/published-requests/{requestId}/closure'].post.responses['200'].headers.ETag")
                        .exists());
    }

    private Fixture fixture() {
        var organizerId = UUID.randomUUID();
        var memberId = UUID.randomUUID();
        var outsiderId = UUID.randomUUID();
        var groupId = UUID.randomUUID();
        var planId = UUID.randomUUID();
        var historicalRequestId = UUID.randomUUID();
        var currentRequestId = UUID.randomUUID();
        var providerIds = java.util.stream.Stream.generate(UUID::randomUUID)
                .limit(2)
                .sorted(java.util.Comparator.comparing(UUID::toString))
                .toList();

        jdbcClient.sql("""
                        INSERT INTO identity_account (account_id, display_name)
                        VALUES (:organizerId, 'Closure organizer'),
                               (:memberId, 'Closure member'),
                               (:outsiderId, 'Closure outsider')
                        """)
                .param("organizerId", organizerId)
                .param("memberId", memberId)
                .param("outsiderId", outsiderId)
                .update();
        jdbcClient.sql("""
                        INSERT INTO group_account (
                            group_id, name, description, status, created_by_account_id
                        )
                        VALUES (:groupId, 'Closure group', '', 'ACTIVE', :organizerId)
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
                        VALUES (:planId, :groupId, 'Closure plan', 'COLLABORATING', :organizerId, 2)
                        """)
                .param("planId", planId)
                .param("groupId", groupId)
                .param("organizerId", organizerId)
                .update();
        insertRequest(historicalRequestId, planId, organizerId, 1, "SUPERSEDED", true);
        transactionTemplate.executeWithoutResult(status -> {
            insertRequest(currentRequestId, planId, organizerId, 2, "OPEN", false);
            jdbcClient.sql("""
                            UPDATE planning_plan
                            SET state = 'OPEN_FOR_OFFERS', current_request_id = :requestId
                            WHERE plan_id = :planId
                            """)
                    .param("requestId", currentRequestId)
                    .param("planId", planId)
                    .update();
        });

        for (var providerId : providerIds) {
            jdbcClient.sql("""
                            INSERT INTO provider_organization (
                                provider_id, display_name, status, verification_status,
                                version, eligibility_version
                            )
                            VALUES (:providerId, :displayName, 'ACTIVE', 'VERIFIED', 2, 2)
                            """)
                    .param("providerId", providerId)
                    .param("displayName", "Closure provider " + providerId)
                    .update();
            jdbcClient.sql("""
                            INSERT INTO marketplace_request_recipient (
                                published_request_id, provider_id, provider_eligibility_version,
                                source, access_state, created_at
                            )
                            VALUES (:requestId, :providerId, 2, 'MATCH_RULE', 'ACTIVE', statement_timestamp())
                            """)
                    .param("requestId", currentRequestId)
                    .param("providerId", providerId)
                    .update();
        }
        return new Fixture(
                organizerId, memberId, outsiderId, groupId, planId,
                historicalRequestId, currentRequestId, providerIds);
    }

    private void insertRequest(
            UUID requestId,
            UUID planId,
            UUID organizerId,
            long requestVersion,
            String state,
            boolean terminal) {
        jdbcClient.sql("""
                        INSERT INTO planning_published_request (
                            request_id, plan_id, request_version, state, distribution_mode,
                            category, time_zone, area_code, radius_km, requested_starts_at,
                            requested_ends_at, minimum_headcount, maximum_headcount,
                            budget_currency, budget_minimum_minor_units, budget_maximum_minor_units,
                            must_haves, provider_safe_notes, category_attributes, offer_deadline,
                            published_by_account_id, published_at, closed_at
                        )
                        VALUES (
                            :requestId, :planId, :requestVersion, :state, 'MATCHED_POOL',
                            'COURT', 'Asia/Manila', 'BGC', 5,
                            statement_timestamp() + interval '2 days',
                            statement_timestamp() + interval '2 days 2 hours',
                            4, 10, 'PHP', 100000, 250000,
                            ARRAY['parking']::varchar[], 'Indoor court preferred.',
                            '{"courtCount":2}'::jsonb,
                            statement_timestamp() + interval '1 day',
                            :organizerId, statement_timestamp() - interval '1 hour',
                            CASE WHEN :terminal THEN statement_timestamp() - interval '30 minutes' ELSE NULL END
                        )
                        """)
                .param("requestId", requestId)
                .param("planId", planId)
                .param("requestVersion", requestVersion)
                .param("state", state)
                .param("organizerId", organizerId)
                .param("terminal", terminal)
                .update();
    }

    private ResultActions closeAs(
            UUID actorId,
            UUID requestId,
            String idempotencyKey,
            String etag) throws Exception {
        var request = post("/api/v1/published-requests/{requestId}/closure", requestId)
                .with(actor(actorId))
                .header("Idempotency-Key", idempotencyKey)
                .header(CorrelationIdFilter.HEADER_NAME, "closure-test");
        if (etag != null) {
            request.header("If-Match", etag);
        }
        return mockMvc.perform(request);
    }

    private ClosePublishedRequestCommand command(Fixture fixture, String key, long expectedVersion) {
        return new ClosePublishedRequestCommand(
                fixture.organizerId(), fixture.currentRequestId(), expectedVersion, key, "closure-test");
    }

    private Object closeAfterBarrier(ClosePublishedRequestCommand command, CyclicBarrier barrier) throws Exception {
        barrier.await(10, TimeUnit.SECONDS);
        try {
            return closureService.close(command);
        } catch (RuntimeException exception) {
            return exception;
        }
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

    private void assertRequestState(UUID requestId, String state, boolean terminal) {
        var row = jdbcClient.sql("""
                        SELECT state, closed_at IS NOT NULL AS terminal
                        FROM planning_published_request
                        WHERE request_id = :requestId
                        """)
                .param("requestId", requestId)
                .query((resultSet, rowNum) -> new RequestStateRow(
                        resultSet.getString("state"), resultSet.getBoolean("terminal")))
                .single();
        assertThat(row.state()).isEqualTo(state);
        assertThat(row.terminal()).isEqualTo(terminal);
    }

    private List<UUID> recipientIds(UUID requestId) {
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

    private List<String> closedBusinessKeys(UUID requestId) {
        return jdbcClient.sql("""
                        SELECT business_key
                        FROM messaging_outbox
                        WHERE aggregate_id = :requestId AND event_type = 'ProviderRequestClosed'
                        ORDER BY business_key
                        """)
                .param("requestId", requestId)
                .query(String.class)
                .list();
    }

    private void assertSafeClosurePayloads(UUID requestId) {
        var payloads = jdbcClient.sql("""
                        SELECT payload::text
                        FROM messaging_outbox
                        WHERE aggregate_id = :requestId AND event_type = 'ProviderRequestClosed'
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
            UUID historicalRequestId,
            UUID currentRequestId,
            List<UUID> providerIds) {
    }

    private record PlanRow(String state, UUID currentRequestId, long version) {
    }

    private record RequestStateRow(String state, boolean terminal) {
    }
}

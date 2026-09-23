package com.builtbyjuls.arat.planning.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.builtbyjuls.arat.PostgreSqlIntegrationTest;
import com.builtbyjuls.arat.identity.api.AuthenticatedActor;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PublishedRequestQueryIT extends PostgreSqlIntegrationTest {

    private static final Set<String> RESPONSE_FIELDS = Set.of(
            "requestId", "requestVersion", "publishedAt", "closedAt", "state", "actionable",
            "category", "timeZone", "area", "requestedWindow", "headcount", "budget", "mustHaves",
            "categoryAttributes", "providerSafeNotes", "offerDeadline");

    @Autowired private WebApplicationContext context;
    @Autowired private JdbcClient jdbcClient;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private ObjectMapper objectMapper;
    private MockMvc mockMvc;
    private TransactionTemplate transactions;

    @BeforeEach
    void prepareClient() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        transactions = new TransactionTemplate(transactionManager);
    }

    @Test
    void readsCurrentDetailAndFrozenHistoryForActiveMembers() throws Exception {
        var fixture = fixture();
        var current = current(fixture.planId(), fixture.memberId())
                .andExpect(status().isOk()).andExpect(jsonPath("$.requestVersion").value(3))
                .andExpect(jsonPath("$.state").value("OPEN")).andExpect(jsonPath("$.actionable").value(true))
                .andExpect(jsonPath("$.closedAt").doesNotExist()).andExpect(jsonPath("$.recipients").doesNotExist())
                .andExpect(jsonPath("$.groupId").doesNotExist()).andExpect(jsonPath("$.publishedByAccountId").doesNotExist())
                .andReturn();
        assertAllowedResponseFields(current.getResponse().getContentAsString());
        var detail = detail(fixture.planId(), fixture.requestId(2), fixture.memberId())
                .andExpect(status().isOk()).andExpect(jsonPath("$.requestVersion").value(2))
                .andExpect(jsonPath("$.state").value("SUPERSEDED")).andExpect(jsonPath("$.actionable").value(false))
                .andExpect(jsonPath("$.closedAt").exists()).andExpect(jsonPath("$.providerSafeNotes").value("version two"))
                .andExpect(jsonPath("$.planId").doesNotExist()).andExpect(jsonPath("$.recipients").doesNotExist())
                .andReturn();
        assertAllowedResponseFields(detail.getResponse().getContentAsString());
        var history = history(fixture.planId(), fixture.memberId(), null, 20)
                .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(3))
                .andExpect(jsonPath("$.items[0].requestVersion").value(3))
                .andExpect(jsonPath("$.items[1].requestVersion").value(2))
                .andExpect(jsonPath("$.items[2].requestVersion").value(1))
                .andExpect(jsonPath("$.items[0].groupId").doesNotExist())
                .andExpect(jsonPath("$.items[0].recipients").doesNotExist())
                .andReturn();
        objectMapper.readTree(history.getResponse().getContentAsByteArray()).path("items")
                .forEach(this::assertAllowedResponseFields);
    }

    @Test
    void privateResourceFailuresAreIndistinguishable() throws Exception {
        var fixture = fixture();
        var outsider = detail(fixture.planId(), fixture.requestId(3), fixture.outsiderId()).andExpect(status().isNotFound()).andReturn();
        var unknown = detail(fixture.planId(), UUID.randomUUID(), fixture.memberId()).andExpect(status().isNotFound()).andReturn();
        var wrongPlan = detail(otherPlan(fixture), fixture.requestId(3), fixture.memberId()).andExpect(status().isNotFound()).andReturn();
        assertThat(shape(outsider.getResponse().getContentAsString()))
                .isEqualTo(shape(unknown.getResponse().getContentAsString()))
                .isEqualTo(shape(wrongPlan.getResponse().getContentAsString()));
        current(fixture.planId(), fixture.outsiderId()).andExpect(status().isNotFound());
        history(fixture.planId(), fixture.outsiderId(), null, 20).andExpect(status().isNotFound());
        deactivate(fixture);
        current(fixture.planId(), fixture.memberId()).andExpect(status().isNotFound());
        history(fixture.planId(), fixture.memberId(), null, 20).andExpect(status().isNotFound());
    }

    @Test
    void historyCursorDoesNotDuplicateOrSkipVersionsAfterReplacement() throws Exception {
        var fixture = fixture();
        var first = history(fixture.planId(), fixture.memberId(), null, 2).andExpect(status().isOk()).andReturn();
        var cursor = objectMapper.readTree(first.getResponse().getContentAsByteArray()).path("nextCursor").asText();
        replaceCurrent(fixture);
        current(fixture.planId(), fixture.memberId()).andExpect(status().isOk())
                .andExpect(jsonPath("$.requestVersion").value(4)).andExpect(jsonPath("$.providerSafeNotes").value("version four"));
        detail(fixture.planId(), fixture.requestId(3), fixture.memberId()).andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("SUPERSEDED")).andExpect(jsonPath("$.providerSafeNotes").value("version three"));
        history(fixture.planId(), fixture.memberId(), cursor, 2)
                .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].requestVersion").value(1));
    }

    @Test
    void retainsTerminalHistoryWithoutCurrentPointerAndMakesExpiredCurrentNonActionable() throws Exception {
        var closed = fixture();
        terminalizeCurrent(closed, "CLOSED", "COLLABORATING");
        current(closed.planId(), closed.memberId()).andExpect(status().isNotFound());
        detail(closed.planId(), closed.requestId(3), closed.memberId())
                .andExpect(status().isOk()).andExpect(jsonPath("$.state").value("CLOSED"))
                .andExpect(jsonPath("$.actionable").value(false)).andExpect(jsonPath("$.closedAt").exists())
                .andExpect(jsonPath("$.providerSafeNotes").value("version three"));
        history(closed.planId(), closed.memberId(), null, 20)
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].state").value("CLOSED"));

        var cancelled = fixture();
        terminalizeCurrent(cancelled, "CANCELLED", "CANCELLED");
        current(cancelled.planId(), cancelled.memberId()).andExpect(status().isNotFound());
        detail(cancelled.planId(), cancelled.requestId(3), cancelled.memberId())
                .andExpect(status().isOk()).andExpect(jsonPath("$.state").value("CANCELLED"))
                .andExpect(jsonPath("$.actionable").value(false)).andExpect(jsonPath("$.closedAt").exists());
        history(cancelled.planId(), cancelled.memberId(), null, 20)
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].state").value("CANCELLED"));

        var expired = fixture(OffsetDateTime.now(ZoneOffset.UTC).minusDays(2).truncatedTo(ChronoUnit.MICROS));
        current(expired.planId(), expired.memberId())
                .andExpect(status().isOk()).andExpect(jsonPath("$.state").value("OPEN"))
                .andExpect(jsonPath("$.actionable").value(false));
    }

    @Test
    void publishesFrozenRoutesInOpenApi() throws Exception {
        mockMvc.perform(get("/v3/api-docs")).andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/v1/plans/{planId}/published-requests/current'].get.operationId").value("readCurrentGroupPublishedRequest"))
                .andExpect(jsonPath("$.paths['/api/v1/plans/{planId}/published-requests/{requestId}'].get.operationId").value("readGroupPublishedRequestHistoryItem"))
                .andExpect(jsonPath("$.paths['/api/v1/plans/{planId}/published-requests'].get.operationId").value("listGroupPublishedRequestHistory"));
    }

    private ResultActions current(UUID planId, UUID actorId) throws Exception {
        return mockMvc.perform(get("/api/v1/plans/{planId}/published-requests/current", planId).with(actor(actorId)));
    }
    private ResultActions detail(UUID planId, UUID requestId, UUID actorId) throws Exception {
        return mockMvc.perform(get("/api/v1/plans/{planId}/published-requests/{requestId}", planId, requestId).with(actor(actorId)));
    }
    private ResultActions history(UUID planId, UUID actorId, String cursor, int limit) throws Exception {
        var request = get("/api/v1/plans/{planId}/published-requests", planId).with(actor(actorId)).param("limit", Integer.toString(limit));
        if (cursor != null) request.param("cursor", cursor);
        return mockMvc.perform(request);
    }

    private Fixture fixture() {
        return fixture(OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS));
    }

    private Fixture fixture(OffsetDateTime now) {
        var organizerId = UUID.randomUUID(); var memberId = UUID.randomUUID(); var outsiderId = UUID.randomUUID();
        var groupId = UUID.randomUUID(); var planId = UUID.randomUUID();
        var one = UUID.randomUUID(); var two = UUID.randomUUID(); var three = UUID.randomUUID();
        jdbcClient.sql("INSERT INTO identity_account (account_id, display_name) VALUES (:organizer, 'Request history organizer'), (:member, 'Request history member'), (:outsider, 'Request history outsider')")
                .param("organizer", organizerId).param("member", memberId).param("outsider", outsiderId).update();
        jdbcClient.sql("INSERT INTO group_account (group_id, name, description, status, created_by_account_id) VALUES (:groupId, 'Request history group', '', 'ACTIVE', :organizer)")
                .param("groupId", groupId).param("organizer", organizerId).update();
        jdbcClient.sql("INSERT INTO group_membership (group_id, account_id, role, status, joined_at) VALUES (:groupId, :organizer, 'ORGANIZER', 'ACTIVE', statement_timestamp()), (:groupId, :member, 'MEMBER', 'ACTIVE', statement_timestamp())")
                .param("groupId", groupId).param("organizer", organizerId).param("member", memberId).update();
        jdbcClient.sql("INSERT INTO planning_plan (plan_id, group_id, title, state, created_by_account_id, version) VALUES (:planId, :groupId, 'Request history plan', 'COLLABORATING', :organizer, 3)")
                .param("planId", planId).param("groupId", groupId).param("organizer", organizerId).update();
        insertRequest(one, planId, organizerId, 1, "CLOSED", "version one", now.minusHours(3));
        insertRequest(two, planId, organizerId, 2, "SUPERSEDED", "version two", now.minusHours(2));
        transactions.executeWithoutResult(ignored -> {
            jdbcClient.sql("UPDATE planning_plan SET state = 'OPEN_FOR_OFFERS', current_request_id = :requestId WHERE plan_id = :planId")
                    .param("planId", planId).param("requestId", three).update();
            insertRequest(three, planId, organizerId, 3, "OPEN", "version three", now.minusHours(1));
        });
        return new Fixture(organizerId, memberId, outsiderId, groupId, planId, now, List.of(one, two, three));
    }

    private UUID otherPlan(Fixture fixture) {
        var planId = UUID.randomUUID();
        jdbcClient.sql("INSERT INTO planning_plan (plan_id, group_id, title, state, created_by_account_id, version) VALUES (:planId, :groupId, 'Other request history plan', 'COLLABORATING', :organizerId, 1)")
                .param("planId", planId).param("groupId", fixture.groupId()).param("organizerId", fixture.organizerId()).update();
        return planId;
    }

    private void deactivate(Fixture fixture) {
        jdbcClient.sql("UPDATE group_membership SET status = 'LEFT', ended_at = statement_timestamp() WHERE group_id = :groupId AND account_id = :accountId")
                .param("groupId", fixture.groupId()).param("accountId", fixture.memberId()).update();
    }

    private void terminalizeCurrent(Fixture fixture, String requestState, String planState) {
        transactions.executeWithoutResult(ignored -> {
            jdbcClient.sql("UPDATE planning_published_request SET state = :requestState, closed_at = clock_timestamp() WHERE request_id = :requestId")
                    .param("requestState", requestState).param("requestId", fixture.requestId(3)).update();
            jdbcClient.sql("UPDATE planning_plan SET state = :planState, current_request_id = NULL, version = version + 1 WHERE plan_id = :planId")
                    .param("planState", planState).param("planId", fixture.planId()).update();
        });
    }

    private void replaceCurrent(Fixture fixture) {
        var replacement = UUID.randomUUID();
        transactions.executeWithoutResult(ignored -> {
            jdbcClient.sql("UPDATE planning_published_request SET state = 'SUPERSEDED', closed_at = clock_timestamp() WHERE request_id = :requestId").param("requestId", fixture.requestId(3)).update();
            jdbcClient.sql("UPDATE planning_plan SET current_request_id = :replacement, version = version + 1 WHERE plan_id = :planId").param("planId", fixture.planId()).param("replacement", replacement).update();
            insertRequest(replacement, fixture.planId(), fixture.organizerId(), 4, "OPEN", "version four", fixture.now());
        });
    }

    private void insertRequest(UUID requestId, UUID planId, UUID organizerId, long version, String state, String notes, OffsetDateTime publishedAt) {
        var deadline = publishedAt.plusDays(1);
        jdbcClient.sql("""
                        INSERT INTO planning_published_request (request_id, plan_id, request_version, state, distribution_mode, category, time_zone, area_code, radius_km, requested_starts_at, requested_ends_at, minimum_headcount, maximum_headcount, budget_currency, budget_minimum_minor_units, budget_maximum_minor_units, must_haves, provider_safe_notes, category_attributes, offer_deadline, published_by_account_id, published_at, closed_at)
                        VALUES (:requestId, :planId, :version, :state, 'MATCHED_POOL', 'COURT', 'Asia/Manila', 'BGC', 5, :startsAt, :endsAt, 4, 10, 'PHP', 100000, 250000, CAST(ARRAY['parking'] AS varchar[]), :notes, CAST('{"courtCount":2}' AS jsonb), :deadline, :organizerId, :publishedAt, CASE WHEN :state = 'OPEN' THEN NULL ELSE :closedAt END)
                        """)
                .param("requestId", requestId).param("planId", planId).param("version", version).param("state", state)
                .param("startsAt", deadline.plusDays(1)).param("endsAt", deadline.plusDays(1).plusHours(2)).param("notes", notes)
                .param("deadline", deadline).param("organizerId", organizerId).param("publishedAt", publishedAt).param("closedAt", publishedAt.plusMinutes(30)).update();
    }

    private List<Object> shape(String body) throws Exception {
        var problem = objectMapper.readTree(body);
        return List.of(problem.path("status").asInt(), problem.path("code").asText(), problem.path("title").asText(), problem.path("detail").asText(), problem.path("violations").toString());
    }

    private void assertAllowedResponseFields(String body) throws Exception {
        assertAllowedResponseFields(objectMapper.readTree(body));
    }

    private void assertAllowedResponseFields(tools.jackson.databind.JsonNode response) {
        assertThat(response.propertyNames()).containsOnlyElementsOf(RESPONSE_FIELDS);
    }

    private org.springframework.test.web.servlet.request.RequestPostProcessor actor(UUID accountId) {
        return authentication(new TestingAuthenticationToken(new AuthenticatedActor(accountId, Set.of()), null, "ROLE_USER"));
    }

    private record Fixture(UUID organizerId, UUID memberId, UUID outsiderId, UUID groupId, UUID planId, OffsetDateTime now, List<UUID> requestIds) {
        UUID requestId(int version) { return requestIds.get(version - 1); }
    }
}

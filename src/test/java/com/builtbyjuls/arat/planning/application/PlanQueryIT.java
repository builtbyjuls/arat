package com.builtbyjuls.arat.planning.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.builtbyjuls.arat.PostgreSqlIntegrationTest;
import com.builtbyjuls.arat.identity.api.AuthenticatedActor;
import com.builtbyjuls.arat.identity.testing.AratAccountFixture;
import com.builtbyjuls.arat.web.CorrelationIdFilter;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.Base64;
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
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PlanQueryIT extends PostgreSqlIntegrationTest {

    private static final UUID OWNER_ID = AratAccountFixture.OWNER.account().accountId();
    private static final UUID MEMBER_ID = AratAccountFixture.MEMBER.account().accountId();
    private static final UUID OUTSIDER_ID = AratAccountFixture.OUTSIDER.account().accountId();
    private static final UUID GROUP_ID = UUID.fromString("71000000-0000-4000-8000-000000000001");
    private static final String CORRELATION_ID = "plan-query-test-123";

    @Autowired private WebApplicationContext webApplicationContext;
    @Autowired private CorrelationIdFilter correlationIdFilter;
    @Autowired private JdbcClient jdbcClient;
    @Autowired private ObjectMapper objectMapper;

    private MockMvc mockMvc;

    @BeforeEach
    void prepareDatabase() {
        jdbcClient.sql("DELETE FROM planning_requirement_must_have").update();
        jdbcClient.sql("DELETE FROM planning_candidate_window").update();
        jdbcClient.sql("DELETE FROM planning_requirement_draft").update();
        jdbcClient.sql("DELETE FROM planning_plan").update();
        jdbcClient.sql("DELETE FROM group_membership").update();
        jdbcClient.sql("DELETE FROM group_account").update();
        jdbcClient.sql("DELETE FROM identity_account").update();
        for (var fixture : AratAccountFixture.values()) {
            jdbcClient.sql("INSERT INTO identity_account (account_id, display_name) VALUES (:accountId, :displayName)")
                    .param("accountId", fixture.account().accountId()).param("displayName", fixture.account().displayName()).update();
        }
        jdbcClient.sql("INSERT INTO group_account (group_id, name, description, status, created_by_account_id) VALUES (:groupId, 'Query group', '', 'ACTIVE', :ownerId)")
                .param("groupId", GROUP_ID).param("ownerId", OWNER_ID).update();
        membership(OWNER_ID, "ORGANIZER", "ACTIVE");
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).addFilters(correlationIdFilter).apply(springSecurity()).build();
    }

    @Test
    void returnsPrivateDetailWithCompleteRequirementDraftAndNoInternalFields() throws Exception {
        var planId = plan(1, "2026-09-20T00:00:00Z", "Friday court");

        mockMvc.perform(get("/api/v1/plans/{planId}", planId).with(authentication(authenticationFor(OWNER_ID))).header(CorrelationIdFilter.HEADER_NAME, CORRELATION_ID))
                .andExpect(status().isOk()).andExpect(header().string("ETag", "\"1\""))
                .andExpect(jsonPath("$.planId").value(planId.toString())).andExpect(jsonPath("$.title").value("Friday court"))
                .andExpect(jsonPath("$.requirements.category").value("COURT"))
                .andExpect(jsonPath("$.requirements.candidateWindows[0].id").exists())
                .andExpect(jsonPath("$.requirements.budget.minimumAmount").value("0.00"))
                .andExpect(jsonPath("$.createdByAccountId").doesNotExist()).andExpect(jsonPath("$.createdAt").doesNotExist())
                .andExpect(jsonPath("$.updatedAt").doesNotExist()).andExpect(jsonPath("$.requirements.categoryAttributes.hasParking").value(true));
    }

    @Test
    void pagesEmptyFirstMiddleAndFinalPagesWithEqualTimestampsAndUpdateStability() throws Exception {
        mockMvc.perform(get("/api/v1/groups/{groupId}/plans", GROUP_ID).with(authentication(authenticationFor(OWNER_ID))).header(CorrelationIdFilter.HEADER_NAME, CORRELATION_ID))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items").isEmpty()).andExpect(jsonPath("$.nextCursor").value(org.hamcrest.Matchers.nullValue()));
        var oldest = plan(1, "2026-09-20T00:00:00Z", "Oldest");
        var middle = plan(2, "2026-09-20T01:00:00Z", "Middle");
        var newest = plan(3, "2026-09-20T01:00:00Z", "Newest");

        var first = page(OWNER_ID, null, 1);
        assertThat(first.at("/items/0/planId").asString()).isEqualTo(newest.toString());
        var firstCursor = first.at("/nextCursor").asString();
        jdbcClient.sql("UPDATE planning_plan SET title = 'Updated newest', version = version + 1 WHERE plan_id = :planId").param("planId", newest).update();
        var second = page(OWNER_ID, firstCursor, 1);
        assertThat(second.at("/items/0/planId").asString()).isEqualTo(middle.toString());
        var third = page(OWNER_ID, second.at("/nextCursor").asString(), 1);
        assertThat(third.at("/items/0/planId").asString()).isEqualTo(oldest.toString());
        assertThat(third.at("/nextCursor").isNull()).isTrue();
    }

    @Test
    void rejectsBadLimitsAndMalformedOrUnsupportedCursors() throws Exception {
        var planId = plan(1, "2026-09-20T00:00:00Z", "Cursor plan");
        for (var query : new String[] {"?limit=0", "?limit=101", "?cursor=bad=cursor", "?cursor=" + encoded("null"), "?cursor=" + encoded("{\"version\":1,\"createdAt\":\"not-a-timestamp\",\"planId\":\"" + planId + "\"}"), "?cursor=" + encoded("{\"version\":1,\"createdAt\":\"+500000-01-01T00:00:00Z\",\"planId\":\"" + planId + "\"}"), "?cursor=" + encoded("{\"version\":1,\"createdAt\":\"+294276-12-31T23:00:00-08:00\",\"planId\":\"" + planId + "\"}"), "?cursor=" + encoded("{\"version\":2,\"createdAt\":\"2026-09-20T00:00:00Z\",\"planId\":\"" + planId + "\"}")}) {
            mockMvc.perform(get("/api/v1/groups/{groupId}/plans" + query, GROUP_ID).with(authentication(authenticationFor(OWNER_ID))).header(CorrelationIdFilter.HEADER_NAME, CORRELATION_ID))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_CURSOR"));
        }
        mockMvc.perform(get("/api/v1/groups/{groupId}/plans?cursor=" + encoded("{\"version\":1,\"createdAt\":\"2026-09-20T00:00:00+18:00\",\"planId\":\"" + planId + "\"}"), GROUP_ID).with(authentication(authenticationFor(OWNER_ID))).header(CorrelationIdFilter.HEADER_NAME, CORRELATION_ID))
                .andExpect(status().isOk());
    }

    @Test
    void hidesMissingAndInaccessibleGroupsAndPlansWithTheSameProblem() throws Exception {
        var planId = plan(1, "2026-09-20T00:00:00Z", "Private plan");
        membership(MEMBER_ID, "MEMBER", "REMOVED");
        membership(OUTSIDER_ID, "MEMBER", "LEFT");
        var outsider = problem(get("/api/v1/plans/{planId}", planId), OUTSIDER_ID);
        var removed = problem(get("/api/v1/plans/{planId}", planId), MEMBER_ID);
        var missing = problem(get("/api/v1/plans/{planId}", UUID.randomUUID()), OWNER_ID);
        var left = problem(get("/api/v1/groups/{groupId}/plans", GROUP_ID), OUTSIDER_ID);
        var outsiderList = problem(get("/api/v1/groups/{groupId}/plans", GROUP_ID), UUID.randomUUID());
        var missingGroup = problem(get("/api/v1/groups/{groupId}/plans", UUID.randomUUID()), OWNER_ID);
        assertThat(withoutInstance(outsider)).isEqualTo(withoutInstance(removed)).isEqualTo(withoutInstance(missing))
                .isEqualTo(withoutInstance(left)).isEqualTo(withoutInstance(outsiderList)).isEqualTo(withoutInstance(missingGroup));
    }

    @Test
    void publishesPrivatePlanReadOperationsInExecutableOpenApi() throws Exception {
        mockMvc.perform(get("/v3/api-docs")).andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/v1/plans/{planId}'].get.operationId").value("readPlan"))
                .andExpect(jsonPath("$.paths['/api/v1/groups/{groupId}/plans'].get.operationId").value("listPlans"))
                .andExpect(jsonPath("$.paths['/api/v1/plans/{planId}'].get.responses['200'].headers.ETag").exists());
    }

    private JsonNode page(UUID accountId, String cursor, int limit) throws Exception {
        var request = get("/api/v1/groups/{groupId}/plans", GROUP_ID).param("limit", String.valueOf(limit));
        if (cursor != null) request.param("cursor", cursor);
        return objectMapper.readTree(mockMvc.perform(request.with(authentication(authenticationFor(accountId))).header(CorrelationIdFilter.HEADER_NAME, CORRELATION_ID))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    private String problem(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request, UUID accountId) throws Exception {
        return mockMvc.perform(request.with(authentication(authenticationFor(accountId))).header(CorrelationIdFilter.HEADER_NAME, CORRELATION_ID))
                .andExpect(status().isNotFound()).andReturn().getResponse().getContentAsString();
    }

    private UUID plan(int suffix, String createdAt, String title) {
        var planId = UUID.fromString("72000000-0000-4000-8000-00000000000" + suffix);
        var timestamp = OffsetDateTime.parse(createdAt);
        jdbcClient.sql("INSERT INTO planning_plan (plan_id, group_id, title, state, created_by_account_id, version, created_at, updated_at) VALUES (:planId, :groupId, :title, 'COLLABORATING', :ownerId, 1, :createdAt, :createdAt)")
                .param("planId", planId).param("groupId", GROUP_ID).param("title", title).param("ownerId", OWNER_ID).param("createdAt", timestamp).update();
        jdbcClient.sql("INSERT INTO planning_requirement_draft (plan_id, category, time_zone, area_code, radius_km, minimum_headcount, maximum_headcount, budget_currency, budget_minimum_minor_units, budget_maximum_minor_units, provider_safe_notes, category_attributes) VALUES (:planId, 'COURT', 'Asia/Manila', 'BGC', 5, 4, 10, 'PHP', 0, 250000, 'Indoor court preferred.', '{\"hasParking\":true}'::jsonb)").param("planId", planId).update();
        jdbcClient.sql("INSERT INTO planning_candidate_window (candidate_window_id, plan_id, sort_order, starts_at, ends_at) VALUES (:windowId, :planId, 1, :startsAt, :endsAt)")
                .param("windowId", UUID.nameUUIDFromBytes(planId.toString().getBytes(StandardCharsets.UTF_8))).param("planId", planId).param("startsAt", timestamp).param("endsAt", timestamp.plusHours(2)).update();
        jdbcClient.sql("INSERT INTO planning_requirement_must_have (plan_id, must_have, sort_order) VALUES (:planId, 'parking', 1)").param("planId", planId).update();
        return planId;
    }

    private void membership(UUID accountId, String role, String status) {
        jdbcClient.sql("INSERT INTO group_membership (group_id, account_id, role, status, joined_at, ended_at) VALUES (:groupId, :accountId, :role, :status, statement_timestamp(), CASE WHEN :status = 'ACTIVE' THEN NULL ELSE statement_timestamp() END)")
                .param("groupId", GROUP_ID).param("accountId", accountId).param("role", role).param("status", status).update();
    }

    private TestingAuthenticationToken authenticationFor(UUID accountId) { return new TestingAuthenticationToken(new AuthenticatedActor(accountId, Set.of()), null, Set.of()); }
    private String encoded(String value) { return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8)); }
    private String withoutInstance(String problem) throws Exception { var json = objectMapper.readTree(problem); ((tools.jackson.databind.node.ObjectNode) json).remove("instance"); return json.toString(); }
}

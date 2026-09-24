package com.builtbyjuls.arat.planning.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.builtbyjuls.arat.PostgreSqlIntegrationTest;
import com.builtbyjuls.arat.identity.api.AuthenticatedActor;
import com.builtbyjuls.arat.web.CorrelationIdFilter;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.Base64;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class RequirementFinalizationQueryIT extends PostgreSqlIntegrationTest {

    private static final Set<String> RESPONSE_FIELDS = Set.of(
            "finalizationId", "basisPlanVersion", "candidateWindowId", "selectedStartAt", "selectedEndAt",
            "offerDeadline", "category", "timeZone", "area", "headcount", "budget", "mustHaves",
            "providerSafeNotes", "categoryAttributes", "currentPreferenceCount", "stalePreferenceCount",
            "warnings", "currentBasis");
    private static final UUID ORGANIZER_ID = UUID.fromString("89000000-0000-4000-8000-000000000001");
    private static final UUID MEMBER_ID = UUID.fromString("89000000-0000-4000-8000-000000000002");
    private static final UUID OUTSIDER_ID = UUID.fromString("89000000-0000-4000-8000-000000000003");
    private static final UUID GROUP_ID = UUID.fromString("89000000-0000-4000-8000-000000000010");
    private static final UUID PLAN_ID = UUID.fromString("89000000-0000-4000-8000-000000000011");
    private static final UUID WINDOW_ID = UUID.fromString("89000000-0000-4000-8000-000000000012");

    @Autowired private WebApplicationContext context;
    @Autowired private CorrelationIdFilter correlationIdFilter;
    @Autowired private JdbcClient jdbcClient;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private PlatformTransactionManager transactionManager;
    private MockMvc mockMvc;
    private TransactionTemplate transactions;

    @BeforeEach
    void prepareDatabase() {
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
        jdbcClient.sql("INSERT INTO identity_account (account_id, display_name) VALUES (:organizer, 'History organizer'), (:member, 'History member'), (:outsider, 'History outsider')")
                .param("organizer", ORGANIZER_ID).param("member", MEMBER_ID).param("outsider", OUTSIDER_ID).update();
        jdbcClient.sql("INSERT INTO group_account (group_id, name, description, status, created_by_account_id) VALUES (:groupId, 'History group', '', 'ACTIVE', :organizer)")
                .param("groupId", GROUP_ID).param("organizer", ORGANIZER_ID).update();
        jdbcClient.sql("INSERT INTO group_membership (group_id, account_id, role, status, joined_at) VALUES (:groupId, :organizer, 'ORGANIZER', 'ACTIVE', statement_timestamp()), (:groupId, :member, 'MEMBER', 'ACTIVE', statement_timestamp())")
                .param("groupId", GROUP_ID).param("organizer", ORGANIZER_ID).param("member", MEMBER_ID).update();
        jdbcClient.sql("INSERT INTO planning_plan (plan_id, group_id, title, state, created_by_account_id, version) VALUES (:planId, :groupId, 'History plan', 'COLLABORATING', :organizer, 2)")
                .param("planId", PLAN_ID).param("groupId", GROUP_ID).param("organizer", ORGANIZER_ID).update();
        jdbcClient.sql("INSERT INTO planning_candidate_window (candidate_window_id, plan_id, sort_order, starts_at, ends_at) VALUES (:windowId, :planId, 1, '2027-01-09T09:00:00Z', '2027-01-09T11:00:00Z')")
                .param("windowId", WINDOW_ID).param("planId", PLAN_ID).update();
        mockMvc = MockMvcBuilders.webAppContextSetup(context).addFilters(correlationIdFilter).apply(springSecurity()).build();
        transactions = new TransactionTemplate(transactionManager);
    }

    @Test
    void readsEmptyHistoryThenPagesImmutableFinalizationsWithCurrentBasisStatus() throws Exception {
        history(ORGANIZER_ID, null, 20).andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isEmpty()).andExpect(jsonPath("$.nextCursor").value(org.hamcrest.Matchers.nullValue()));

        var oldest = finalization("89000000-0000-4000-8000-000000000101", 1, "2026-01-01T00:00:00Z");
        var middle = finalization("89000000-0000-4000-8000-000000000102", 2, "2026-01-02T00:00:00Z");
        var newest = finalization("89000000-0000-4000-8000-000000000103", 2, "2026-01-02T00:00:00Z");
        var first = history(MEMBER_ID, null, 2).andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].finalizationId").value(newest.toString()))
                .andExpect(jsonPath("$.items[0].currentBasis").value(true))
                .andExpect(jsonPath("$.items[1].finalizationId").value(middle.toString()))
                .andExpect(jsonPath("$.items[1].currentBasis").value(true))
                .andReturn();
        var cursor = objectMapper.readTree(first.getResponse().getContentAsByteArray()).path("nextCursor").asText();
        var second = history(MEMBER_ID, cursor, 2).andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].finalizationId").value(oldest.toString()))
                .andExpect(jsonPath("$.items[0].currentBasis").value(false))
                .andExpect(jsonPath("$.nextCursor").value(org.hamcrest.Matchers.nullValue()))
                .andReturn();
        objectMapper.readTree(second.getResponse().getContentAsByteArray()).path("items")
                .forEach(this::assertAllowedResponseFields);
    }

    @Test
    void reflectsAStaleBasisAfterRequirementVersionChangesWithoutReorderingHistory() throws Exception {
        var newest = finalization("89000000-0000-4000-8000-000000000103", 2, "2026-01-02T00:00:00Z");
        finalization("89000000-0000-4000-8000-000000000102", 1, "2026-01-01T00:00:00Z");
        var first = history(ORGANIZER_ID, null, 1).andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].finalizationId").value(newest.toString()))
                .andExpect(jsonPath("$.items[0].currentBasis").value(true)).andReturn();
        jdbcClient.sql("UPDATE planning_plan SET version = 3 WHERE plan_id = :planId").param("planId", PLAN_ID).update();
        history(ORGANIZER_ID, null, 1).andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].finalizationId").value(newest.toString()))
                .andExpect(jsonPath("$.items[0].currentBasis").value(false));
        history(ORGANIZER_ID, objectMapper.readTree(first.getResponse().getContentAsByteArray()).path("nextCursor").asText(), 1)
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].basisPlanVersion").value(1));
    }

    @Test
    void hidesPrivateHistoryBeforeCursorValidation() throws Exception {
        finalization("89000000-0000-4000-8000-000000000101", 2, "2026-01-01T00:00:00Z");
        var outsider = history(OUTSIDER_ID, "not-a-cursor", 20).andExpect(status().isNotFound()).andReturn();
        var missing = history(ORGANIZER_ID, UUID.randomUUID(), "not-a-cursor", 20).andExpect(status().isNotFound()).andReturn();
        jdbcClient.sql("UPDATE group_membership SET status = 'LEFT', ended_at = statement_timestamp() WHERE group_id = :groupId AND account_id = :memberId")
                .param("groupId", GROUP_ID).param("memberId", MEMBER_ID).update();
        var formerMember = history(MEMBER_ID, "not-a-cursor", 20).andExpect(status().isNotFound()).andReturn();
        assertThat(problemShape(outsider.getResponse().getContentAsString()))
                .isEqualTo(problemShape(missing.getResponse().getContentAsString()))
                .isEqualTo(problemShape(formerMember.getResponse().getContentAsString()));
    }

    @Test
    void rejectsInvalidLimitsAndActorOrPlanBoundCursors() throws Exception {
        finalization("89000000-0000-4000-8000-000000000101", 2, "2026-01-01T00:00:00Z");
        finalization("89000000-0000-4000-8000-000000000102", 2, "2026-01-02T00:00:00Z");
        for (var cursor : List.of("bad=cursor", encoded("null"), encoded("{\"version\":2}"),
                encoded("{\"version\":1,\"actorId\":\"" + ORGANIZER_ID + "\",\"planId\":\"" + PLAN_ID + "\",\"createdAt\":\"not-a-timestamp\",\"finalizationId\":\"" + UUID.randomUUID() + "\"}"),
                encoded("{\"version\":1,\"actorId\":\"" + ORGANIZER_ID + "\",\"planId\":\"" + UUID.randomUUID() + "\",\"createdAt\":\"2026-01-01T00:00:00Z\",\"finalizationId\":\"" + UUID.randomUUID() + "\"}"))) {
            history(ORGANIZER_ID, cursor, 20).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_CURSOR"));
        }
        history(ORGANIZER_ID, null, 0).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_CURSOR"));
        var first = history(ORGANIZER_ID, null, 1).andExpect(status().isOk()).andReturn();
        var cursor = objectMapper.readTree(first.getResponse().getContentAsByteArray()).path("nextCursor").asText();
        history(MEMBER_ID, cursor, 1).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_CURSOR"));
    }

    @Test
    void usesTheFinalizationHistoryIndexAndPublishesTheOpenApiContract() throws Exception {
        finalization("89000000-0000-4000-8000-000000000101", 2, "2026-01-01T00:00:00Z");
        List<String> plan = transactions.execute(status -> {
            jdbcClient.sql("SET LOCAL enable_seqscan = off").update();
            return jdbcClient.sql("""
                            EXPLAIN (COSTS OFF)
                            SELECT finalization_id
                            FROM planning_requirement_finalization
                            WHERE plan_id = :planId
                            ORDER BY created_at DESC, finalization_id DESC
                            LIMIT 2
                            """)
                    .param("planId", PLAN_ID).query(String.class).list();
        });
        assertThat(plan).anyMatch(line -> line.contains("planning_requirement_finalization_plan_created_idx"));
        mockMvc.perform(get("/v3/api-docs")).andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/v1/plans/{planId}/requirement-finalizations'].get.operationId").value("listRequirementFinalizations"))
                .andExpect(jsonPath("$.paths['/api/v1/plans/{planId}/requirement-finalizations'].get.responses['200'].content['application/json'].schema.$ref")
                        .value("#/components/schemas/RequirementFinalizationPageRepresentation"));
    }

    private UUID finalization(String id, long basisPlanVersion, String createdAt) {
        var finalizationId = UUID.fromString(id);
        jdbcClient.sql("""
                        INSERT INTO planning_requirement_finalization (
                            finalization_id, plan_id, basis_plan_version, selected_candidate_window_id,
                            selected_starts_at, selected_ends_at, offer_deadline, category, time_zone,
                            area_code, radius_km, minimum_headcount, maximum_headcount, budget_currency,
                            budget_minimum_minor_units, budget_maximum_minor_units, must_haves,
                            provider_safe_notes, category_attributes, current_preference_count,
                            stale_preference_count, warnings, finalized_by_account_id, created_at
                        ) VALUES (
                            :finalizationId, :planId, :basisPlanVersion, :windowId,
                            '2027-01-09T09:00:00Z', '2027-01-09T11:00:00Z', '2027-01-09T08:00:00Z', 'COURT', 'Asia/Manila',
                            'BGC', 5, 4, 10, 'PHP', 0, 250000, CAST(ARRAY['parking'] AS varchar[]),
                            'Provider-safe note', CAST('{\"courtCount\":2}' AS jsonb), 1,
                            0, CAST(ARRAY[]::varchar[] AS varchar[]), :organizerId, :createdAt
                        )
                        """)
                .param("finalizationId", finalizationId).param("planId", PLAN_ID).param("basisPlanVersion", basisPlanVersion)
                .param("windowId", WINDOW_ID).param("organizerId", ORGANIZER_ID).param("createdAt", OffsetDateTime.parse(createdAt)).update();
        return finalizationId;
    }

    private ResultActions history(UUID actorId, String cursor, int limit) throws Exception {
        return history(PLAN_ID, actorId, cursor, limit);
    }

    private ResultActions history(UUID planId, UUID actorId, String cursor, int limit) throws Exception {
        var request = get("/api/v1/plans/{planId}/requirement-finalizations", planId)
                .with(authentication(new TestingAuthenticationToken(new AuthenticatedActor(actorId, Set.of()), null, "ROLE_USER")))
                .param("limit", Integer.toString(limit));
        if (cursor != null) {
            request.param("cursor", cursor);
        }
        return mockMvc.perform(request);
    }

    private void assertAllowedResponseFields(JsonNode response) {
        assertThat(response.propertyNames()).containsOnlyElementsOf(RESPONSE_FIELDS);
    }

    private List<Object> problemShape(String body) throws Exception {
        var problem = objectMapper.readTree(body);
        return List.of(problem.path("status").asInt(), problem.path("code").asText(), problem.path("title").asText(), problem.path("detail").asText(), problem.path("violations").toString());
    }

    private String encoded(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }
}

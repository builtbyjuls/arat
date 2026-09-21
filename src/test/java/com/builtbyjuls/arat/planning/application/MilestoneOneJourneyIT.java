package com.builtbyjuls.arat.planning.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.builtbyjuls.arat.PostgreSqlIntegrationTest;
import com.builtbyjuls.arat.identity.security.LocalAccountFixtures;
import com.builtbyjuls.arat.web.CorrelationIdFilter;
import io.swagger.v3.parser.OpenAPIV3Parser;
import io.swagger.v3.parser.core.models.ParseOptions;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@ActiveProfiles({"test", "local"})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class MilestoneOneJourneyIT extends PostgreSqlIntegrationTest {

    private static final String OWNER_TOKEN = "arat-local-owner-token";
    private static final String MEMBER_TOKEN = "arat-local-member-token";
    private static final String OUTSIDER_TOKEN = "arat-local-outsider-token";
    private static final String CORRELATION_ID = "m1-journey-123";

    private record OpenApiOperation(String path, String method, String operationId, List<String> responseCodes) {}

    private record OpenApiResponse(String path, String method, String status, String schema) {}

    private record OpenApiHeader(String path, String method, String status, String header) {}

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private CorrelationIdFilter correlationIdFilter;

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private ObjectMapper objectMapper;

    private MockMvc mockMvc;

    @BeforeEach
    void prepareDatabase() {
        jdbcClient.sql("DELETE FROM audit_event").update();
        jdbcClient.sql("DELETE FROM idempotency_record").update();
        jdbcClient.sql("DELETE FROM planning_preference_ranked_item").update();
        jdbcClient.sql("DELETE FROM planning_preference_selected_window").update();
        jdbcClient.sql("DELETE FROM planning_plan_preference").update();
        jdbcClient.sql("DELETE FROM planning_requirement_must_have").update();
        jdbcClient.sql("DELETE FROM planning_candidate_window").update();
        jdbcClient.sql("DELETE FROM planning_requirement_draft").update();
        jdbcClient.sql("DELETE FROM planning_plan").update();
        jdbcClient.sql("DELETE FROM group_invitation").update();
        jdbcClient.sql("DELETE FROM group_membership").update();
        jdbcClient.sql("DELETE FROM group_account").update();
        jdbcClient.sql("DELETE FROM identity_account WHERE account_id IN (:ownerId, :memberId, :outsiderId)")
                .param("ownerId", LocalAccountFixtures.OWNER.accountId())
                .param("memberId", LocalAccountFixtures.MEMBER.accountId())
                .param("outsiderId", LocalAccountFixtures.OUTSIDER.accountId())
                .update();
        for (var account : LocalAccountFixtures.all()) {
            jdbcClient.sql("INSERT INTO identity_account (account_id, display_name) VALUES (:accountId, :displayName)")
                    .param("accountId", account.accountId())
                    .param("displayName", account.displayName())
                    .update();
        }
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .addFilters(correlationIdFilter)
                .apply(springSecurity())
                .build();
    }

    @Test
    void completesThePrivateCollaborationJourneyThroughLocalBearerHttp() throws Exception {
        request(get("/api/v1/dev/whoami"), OWNER_TOKEN)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.actorId").value(LocalAccountFixtures.OWNER.accountId().toString()));

        var groupCreated = request(post("/api/v1/groups")
                        .header("Idempotency-Key", "m1-journey-create-group")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Friday badminton\",\"description\":\"M1 collaboration journey\"}"), OWNER_TOKEN)
                .andExpect(status().isCreated())
                .andExpect(header().string("ETag", "\"1\""))
                .andReturn().getResponse();
        var groupReplay = request(post("/api/v1/groups")
                        .header("Idempotency-Key", "m1-journey-create-group")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Friday badminton\",\"description\":\"M1 collaboration journey\"}"), OWNER_TOKEN)
                .andExpect(status().isCreated())
                .andExpect(header().string("ETag", groupCreated.getHeader("ETag")))
                .andReturn().getResponse();
        assertThat(groupReplay.getContentAsString()).isEqualTo(groupCreated.getContentAsString());
        var groupId = uuid(groupCreated.getContentAsString(), "groupId");

        var invitation = request(post("/api/v1/groups/{groupId}/invites", groupId)
                        .header("Idempotency-Key", "m1-journey-create-invitation")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"inviteeAccountId\":\"%s\",\"expiryHours\":24}".formatted(LocalAccountFixtures.MEMBER.accountId())), OWNER_TOKEN)
                .andExpect(status().isCreated())
                .andReturn().getResponse();
        var invitationToken = value(invitation.getContentAsString(), "token");
        var invitationId = uuid(invitation.getContentAsString(), "inviteId");

        request(delete("/api/v1/groups/{groupId}/invites/{inviteId}", groupId, invitationId)
                        .header("Idempotency-Key", "x".repeat(256)), OWNER_TOKEN)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        request(post("/api/v1/group-invites/{token}/accept", invitationToken)
                        .header("Idempotency-Key", "x".repeat(256)), MEMBER_TOKEN)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        var accepted = request(post("/api/v1/group-invites/{token}/accept", invitationToken)
                        .header("Idempotency-Key", "m1-journey-accept-invitation"), MEMBER_TOKEN)
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"2\""))
                .andReturn().getResponse();
        var acceptedReplay = request(post("/api/v1/group-invites/{token}/accept", invitationToken)
                        .header("Idempotency-Key", "m1-journey-accept-invitation"), MEMBER_TOKEN)
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", accepted.getHeader("ETag")))
                .andReturn().getResponse();
        assertThat(acceptedReplay.getContentAsString()).isEqualTo(accepted.getContentAsString());

        var planCreated = request(post("/api/v1/groups/{groupId}/plans", groupId)
                        .header("Idempotency-Key", "m1-journey-create-plan")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(planRequest()), MEMBER_TOKEN)
                .andExpect(status().isCreated())
                .andExpect(header().string("ETag", "\"1\""))
                .andExpect(jsonPath("$.createdByAccountId").value(LocalAccountFixtures.MEMBER.accountId().toString()))
                .andReturn().getResponse();
        var planId = uuid(planCreated.getContentAsString(), "planId");

        var planDetail = request(get("/api/v1/plans/{planId}", planId), OWNER_TOKEN)
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"1\""))
                .andReturn().getResponse();
        var retainedWindowId = objectMapper.readTree(planDetail.getContentAsString())
                .path("requirements").path("candidateWindows").get(0).path("id").asText();

        request(put("/api/v1/plans/{planId}/members/me/preference", planId)
                        .header("If-None-Match", "*")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(preferenceRequest(1, retainedWindowId, "Owner preference.")), OWNER_TOKEN)
                .andExpect(status().isCreated())
                .andExpect(header().string("ETag", "\"1\""));
        var memberPreference = request(put("/api/v1/plans/{planId}/members/me/preference", planId)
                        .header("If-None-Match", "*")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(preferenceRequest(1, retainedWindowId, "Member preference.")), MEMBER_TOKEN)
                .andExpect(status().isCreated())
                .andExpect(header().string("ETag", "\"1\""))
                .andReturn().getResponse();

        request(put("/api/v1/plans/{planId}/requirements", planId)
                        .header("If-Match", "\"1\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(replacementRequest(retainedWindowId)), OWNER_TOKEN)
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"2\""));
        request(put("/api/v1/plans/{planId}/requirements", planId)
                        .header("If-Match", "\"1\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(replacementRequest(retainedWindowId)), OWNER_TOKEN)
                .andExpect(status().isPreconditionFailed())
                .andExpect(jsonPath("$.code").value("PRECONDITION_FAILED"));

        request(get("/api/v1/plans/{planId}/preferences", planId), OWNER_TOKEN)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[?(@.preference.accountId == '%s')].current".formatted(LocalAccountFixtures.OWNER.accountId())).value(false))
                .andExpect(jsonPath("$.items[?(@.preference.accountId == '%s')].current".formatted(LocalAccountFixtures.MEMBER.accountId())).value(false));

        request(put("/api/v1/plans/{planId}/members/me/preference", planId)
                        .header("If-Match", memberPreference.getHeader("ETag"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(preferenceRequest(2, retainedWindowId, "Refreshed member preference.")), MEMBER_TOKEN)
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"2\""))
                .andExpect(jsonPath("$.basisPlanVersion").value(2));

        request(get("/api/v1/groups/{groupId}/plans", groupId), OWNER_TOKEN)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].planId").value(planId.toString()));
        request(get("/api/v1/plans/{planId}", planId), OWNER_TOKEN)
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"2\""))
                .andExpect(jsonPath("$.requirements.category").value("COURT"));
        request(get("/api/v1/plans/{planId}", planId), OUTSIDER_TOKEN)
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("PRIVATE_RESOURCE_NOT_FOUND"))
                .andExpect(jsonPath("$.planId").doesNotExist());

        var cancelled = request(post("/api/v1/plans/{planId}/cancellation", planId)
                        .header("Idempotency-Key", "m1-journey-cancel-plan")
                        .header("If-Match", "\"2\""), OWNER_TOKEN)
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"3\""))
                .andExpect(jsonPath("$.state").value("CANCELLED"))
                .andReturn().getResponse();
        request(post("/api/v1/plans/{planId}/cancellation", planId)
                        .header("Idempotency-Key", "m1-journey-cancel-plan")
                        .header("If-Match", "\"2\""), OWNER_TOKEN)
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", cancelled.getHeader("ETag")));
        request(put("/api/v1/plans/{planId}/members/me/preference", planId)
                        .header("If-Match", memberPreference.getHeader("ETag"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(preferenceRequest(2, retainedWindowId, "Write after cancellation.")), MEMBER_TOKEN)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_PLAN_STATE"));
        request(get("/api/v1/plans/{planId}", planId), MEMBER_TOKEN)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("CANCELLED"));
        request(get("/api/v1/plans/{planId}/preferences", planId), MEMBER_TOKEN)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2));

        assertThat(auditCount("group.created", groupId, null)).isOne();
        assertThat(auditCount("group.invitation.created", groupId, null)).isOne();
        assertThat(auditCount("group.invitation.accepted", groupId, null)).isOne();
        assertThat(auditCount("plan.created", groupId, planId)).isOne();
        assertThat(auditCount("plan.requirements.replaced", groupId, planId)).isOne();
        assertThat(auditCount("plan.cancelled", groupId, planId)).isOne();
    }

    @Test
    void exposesEveryM1RouteAndProblemCodeInOpenApi() throws Exception {
        var openApi = request(get("/v3/api-docs"), OWNER_TOKEN)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        var parseOptions = new ParseOptions();
        parseOptions.setResolve(false);
        var parsed = new OpenAPIV3Parser().readContents(openApi, null, parseOptions);
        assertThat(parsed.getOpenAPI()).isNotNull();
        assertThat(parsed.getMessages()).isEmpty();
        var root = objectMapper.readTree(openApi);
        var operations = List.of(
                new OpenApiOperation("/api/v1/groups", "post", "createGroup", List.of("201", "400", "401", "409", "422")),
                new OpenApiOperation("/api/v1/groups/{groupId}", "get", "readGroup", List.of("200", "401", "404")),
                new OpenApiOperation("/api/v1/groups/{groupId}/invites", "post", "createGroupInvitation", List.of("201", "400", "401", "403", "404", "409", "422")),
                new OpenApiOperation("/api/v1/groups/{groupId}/invites/{inviteId}", "delete", "revokeGroupInvitation", List.of("204", "400", "401", "403", "404", "409", "422")),
                new OpenApiOperation("/api/v1/group-invites/{token}/accept", "post", "acceptGroupInvitation", List.of("200", "400", "401", "404", "409", "422")),
                new OpenApiOperation("/api/v1/groups/{groupId}/members/me", "delete", "leaveGroup", List.of("204", "400", "401", "404", "409", "422")),
                new OpenApiOperation("/api/v1/groups/{groupId}/members/{accountId}", "delete", "removeGroupMember", List.of("204", "400", "401", "403", "404", "409", "422")),
                new OpenApiOperation("/api/v1/groups/{groupId}/organizer-transfer", "post", "transferGroupOrganizer", List.of("200", "400", "401", "403", "404", "409", "412", "422", "428")),
                new OpenApiOperation("/api/v1/groups/{groupId}/plans", "post", "createCollaborativePlan", List.of("201", "400", "401", "404", "409", "422")),
                new OpenApiOperation("/api/v1/groups/{groupId}/plans", "get", "listPlans", List.of("200", "400", "401", "404")),
                new OpenApiOperation("/api/v1/plans/{planId}", "get", "readPlan", List.of("200", "401", "404")),
                new OpenApiOperation("/api/v1/plans/{planId}/requirements", "put", "replacePlanRequirements", List.of("200", "400", "401", "403", "404", "409", "412", "422", "428")),
                new OpenApiOperation("/api/v1/plans/{planId}/members/me/preference", "put", "putMemberPreference", List.of("200", "201", "400", "401", "404", "409", "412", "422", "428")),
                new OpenApiOperation("/api/v1/plans/{planId}/members/me/preference", "get", "readOwnMemberPreference", List.of("200", "401", "404")),
                new OpenApiOperation("/api/v1/plans/{planId}/preferences", "get", "listPlanPreferences", List.of("200", "401", "404")),
                new OpenApiOperation("/api/v1/plans/{planId}/cancellation", "post", "cancelCollaborativePlan", List.of("200", "400", "401", "403", "404", "409", "412", "422", "428")));
        for (var operation : operations) {
            var documented = root.path("paths").path(operation.path()).path(operation.method());
            assertThat(documented.path("operationId").asText()).isEqualTo(operation.operationId());
            assertThat(documented.path("security").get(0).path("bearerAuth").isArray()).isTrue();
            for (var responseCode : operation.responseCodes()) {
                var response = documented.path("responses").path(responseCode);
                assertThat(response.isObject()).isTrue();
                if (responseCode.startsWith("4")) {
                    assertThat(response.path("content").path("application/problem+json").path("schema").path("$ref").asText())
                            .isEqualTo("#/components/schemas/ApiProblemResponse");
                }
            }
        }
        assertSuccessSchemas(root, List.of(
                new OpenApiResponse("/api/v1/groups", "post", "201", "GroupRepresentation"),
                new OpenApiResponse("/api/v1/groups/{groupId}", "get", "200", "GroupDetailRepresentation"),
                new OpenApiResponse("/api/v1/groups/{groupId}/invites", "post", "201", "InvitationRepresentation"),
                new OpenApiResponse("/api/v1/group-invites/{token}/accept", "post", "200", "GroupMembershipRepresentation"),
                new OpenApiResponse("/api/v1/groups/{groupId}/organizer-transfer", "post", "200", "OrganizerTransferRepresentation"),
                new OpenApiResponse("/api/v1/groups/{groupId}/plans", "post", "201", "PlanRepresentation"),
                new OpenApiResponse("/api/v1/groups/{groupId}/plans", "get", "200", "PlanPageRepresentation"),
                new OpenApiResponse("/api/v1/plans/{planId}", "get", "200", "PlanDetailRepresentation"),
                new OpenApiResponse("/api/v1/plans/{planId}/requirements", "put", "200", "PlanRequirements"),
                new OpenApiResponse("/api/v1/plans/{planId}/members/me/preference", "put", "200", "PlanPreference"),
                new OpenApiResponse("/api/v1/plans/{planId}/members/me/preference", "put", "201", "PlanPreference"),
                new OpenApiResponse("/api/v1/plans/{planId}/members/me/preference", "get", "200", "PlanPreference"),
                new OpenApiResponse("/api/v1/plans/{planId}/preferences", "get", "200", "PreferenceCollectionRepresentation"),
                new OpenApiResponse("/api/v1/plans/{planId}/cancellation", "post", "200", "PlanRepresentation")));
        assertHeaders(root, List.of(
                new OpenApiHeader("/api/v1/groups", "post", "201", "ETag"),
                new OpenApiHeader("/api/v1/groups", "post", "201", "Location"),
                new OpenApiHeader("/api/v1/groups/{groupId}", "get", "200", "ETag"),
                new OpenApiHeader("/api/v1/groups/{groupId}/invites", "post", "201", "Location"),
                new OpenApiHeader("/api/v1/group-invites/{token}/accept", "post", "200", "ETag"),
                new OpenApiHeader("/api/v1/groups/{groupId}/members/me", "delete", "204", "ETag"),
                new OpenApiHeader("/api/v1/groups/{groupId}/members/{accountId}", "delete", "204", "ETag"),
                new OpenApiHeader("/api/v1/groups/{groupId}/organizer-transfer", "post", "200", "ETag"),
                new OpenApiHeader("/api/v1/groups/{groupId}/plans", "post", "201", "ETag"),
                new OpenApiHeader("/api/v1/groups/{groupId}/plans", "post", "201", "Location"),
                new OpenApiHeader("/api/v1/plans/{planId}", "get", "200", "ETag"),
                new OpenApiHeader("/api/v1/plans/{planId}/requirements", "put", "200", "ETag"),
                new OpenApiHeader("/api/v1/plans/{planId}/members/me/preference", "put", "200", "ETag"),
                new OpenApiHeader("/api/v1/plans/{planId}/members/me/preference", "put", "201", "ETag"),
                new OpenApiHeader("/api/v1/plans/{planId}/members/me/preference", "put", "201", "Location"),
                new OpenApiHeader("/api/v1/plans/{planId}/members/me/preference", "get", "200", "ETag"),
                new OpenApiHeader("/api/v1/plans/{planId}/cancellation", "post", "200", "ETag")));
        var codes = root.path("components").path("schemas").path("ApiProblemResponse")
                .path("properties").path("code").path("enum");
        for (var code : List.of(
                "PRIVATE_RESOURCE_NOT_FOUND", "FORBIDDEN_ROLE", "FINAL_ORGANIZER_REQUIRED",
                "INVITATION_UNAVAILABLE", "ALREADY_MEMBER", "PRECONDITION_REQUIRED",
                "INVALID_PRECONDITION", "PRECONDITION_FAILED", "IDEMPOTENCY_KEY_REUSED",
                "INVALID_CURSOR", "INVALID_PLAN_STATE", "INVITATION_ALREADY_PENDING",
                "PREFERENCE_NOT_FOUND", "REQUIREMENT_VERSION_CHANGED", "ALREADY_ORGANIZER",
                "VALIDATION_FAILED")) {
            assertThat(codes).extracting(JsonNode::asText).contains(code);
        }
    }

    private void assertSuccessSchemas(JsonNode root, List<OpenApiResponse> responses) {
        for (var response : responses) {
            assertThat(root.path("paths").path(response.path()).path(response.method())
                    .path("responses").path(response.status()).path("content")
                    .path("application/json").path("schema").path("$ref").asText())
                    .isEqualTo("#/components/schemas/" + response.schema());
        }
    }

    private void assertHeaders(JsonNode root, List<OpenApiHeader> headers) {
        for (var header : headers) {
            assertThat(root.path("paths").path(header.path()).path(header.method())
                    .path("responses").path(header.status()).path("headers").path(header.header()).isObject()).isTrue();
        }
    }

    private org.springframework.test.web.servlet.ResultActions request(
            MockHttpServletRequestBuilder request, String token) throws Exception {
        return mockMvc.perform(request
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .header(CorrelationIdFilter.HEADER_NAME, CORRELATION_ID));
    }

    private UUID uuid(String json, String field) throws Exception {
        return UUID.fromString(value(json, field));
    }

    private String value(String json, String field) throws Exception {
        return objectMapper.readTree(json).path(field).asText();
    }

    private long auditCount(String action, UUID groupId, UUID planId) {
        if (planId == null) {
            return jdbcClient.sql("SELECT count(*) FROM audit_event WHERE action = :action AND group_id = :groupId")
                    .param("action", action)
                    .param("groupId", groupId)
                    .query(Long.class).single();
        }
        return jdbcClient.sql("SELECT count(*) FROM audit_event WHERE action = :action AND group_id = :groupId AND plan_id = :planId")
                .param("action", action)
                .param("groupId", groupId)
                .param("planId", planId)
                .query(Long.class).single();
    }

    private String planRequest() {
        return """
                {"title":"Friday badminton","category":"COURT","timeZone":"Asia/Manila",
                "candidateWindows":[{"startAt":"2027-01-09T09:00:00Z","endAt":"2027-01-09T11:00:00Z"}],
                "area":{"code":"BGC","radiusKm":5},"headcount":{"minimum":4,"maximum":10},
                "budget":{"currency":"PHP","minimumAmount":"0.00","maximumAmount":"2500.00"},
                "mustHaves":["parking","shower"],"providerSafeNotes":"Indoor court preferred.",
                "categoryAttributes":{"hasParking":true,"courtCount":2}}
                """.replaceAll("\\R", "");
    }

    private String replacementRequest(String retainedWindowId) {
        return """
                {"title":"Friday badminton revised","category":"COURT","timeZone":"Asia/Manila",
                "candidateWindows":[{"id":"%s","startAt":"2027-01-09T10:00:00Z","endAt":"2027-01-09T12:00:00Z"}],
                "area":{"code":"BGC","radiusKm":5},"headcount":{"minimum":4,"maximum":10},
                "budget":{"currency":"PHP","minimumAmount":"0.00","maximumAmount":"2500.00"},
                "mustHaves":["parking"],"providerSafeNotes":"Indoor court preferred.",
                "categoryAttributes":{"hasParking":true}}
                """.formatted(retainedWindowId).replaceAll("\\R", "");
    }

    private String preferenceRequest(long basisPlanVersion, String windowId, String privateNote) {
        return """
                {"basisPlanVersion":%d,"attendance":"JOINING","guestCount":1,
                "selectedWindowIds":["%s"],"personalBudget":{"currency":"PHP","amount":"500.00"},
                "rankedPreferences":["indoor court","parking"],"privateNote":"%s"}
                """.formatted(basisPlanVersion, windowId, privateNote).replaceAll("\\R", "");
    }
}

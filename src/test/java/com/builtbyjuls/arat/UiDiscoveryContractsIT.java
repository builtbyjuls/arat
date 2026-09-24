package com.builtbyjuls.arat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.builtbyjuls.arat.identity.security.LocalAccountFixtures;
import com.builtbyjuls.arat.web.CorrelationIdFilter;
import io.swagger.v3.parser.OpenAPIV3Parser;
import io.swagger.v3.parser.core.models.ParseOptions;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
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
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@ActiveProfiles({"test", "local"})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class UiDiscoveryContractsIT extends PostgreSqlIntegrationTest {

    private static final String OWNER_TOKEN = "arat-local-owner-token";
    private static final String MEMBER_TOKEN = "arat-local-member-token";
    private static final String OUTSIDER_TOKEN = "arat-local-outsider-token";
    private static final String PROVIDER_TOKEN = "arat-local-provider-token";
    private static final String OPERATOR_TOKEN = "arat-local-operator-token";
    private static final String CORRELATION_ID = "ui-discovery-contracts-123";

    private static final Set<String> GROUP_FIELDS = Set.of(
            "groupId", "name", "description", "version", "role");
    private static final Set<String> PROVIDER_FIELDS = Set.of(
            "providerId", "displayName", "verificationStatus", "version", "callerStaffRole",
            "supportedCategories", "serviceAreaCodes");
    private static final Set<String> FINALIZATION_FIELDS = Set.of(
            "finalizationId", "basisPlanVersion", "candidateWindowId", "selectedStartAt", "selectedEndAt",
            "offerDeadline", "category", "timeZone", "area", "headcount", "budget", "mustHaves",
            "providerSafeNotes", "categoryAttributes", "currentPreferenceCount", "stalePreferenceCount",
            "warnings", "currentBasis");
    private static final Set<String> VERIFICATION_FIELDS = Set.of(
            "submissionId", "providerId", "providerVersion", "displayName", "verificationStatus",
            "supportedCategories", "serviceAreaCodes", "submittedAt", "evidenceReferences");

    private record OpenApiRead(
            String path,
            String operationId,
            String responseSchema,
            List<String> responseCodes) {}

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
        jdbcClient.sql("""
                TRUNCATE TABLE messaging_outbox, audit_event, idempotency_record,
                    group_account, planning_plan, provider_organization CASCADE
                """).update();
        jdbcClient.sql("DELETE FROM identity_account").update();
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
    void rediscoversEveryUiContextWithoutCrossingActorBorders() throws Exception {
        var groupCreated = request(post("/api/v1/groups")
                        .header("Idempotency-Key", "ui-discovery-create-group")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"UI discovery group\",\"description\":\"Private reload fixture\"}"),
                        OWNER_TOKEN)
                .andExpect(status().isCreated())
                .andReturn();
        var groupId = uuid(groupCreated, "groupId");

        var startAt = databaseNow().plusDays(30);
        var planCreated = request(post("/api/v1/groups/{groupId}/plans", groupId)
                        .header("Idempotency-Key", "ui-discovery-create-plan")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(planRequest(startAt, startAt.plusHours(2))), OWNER_TOKEN)
                .andExpect(status().isCreated())
                .andExpect(header().string(HttpHeaders.ETAG, "\"1\""))
                .andReturn();
        var planId = uuid(planCreated, "planId");
        var plan = request(get("/api/v1/plans/{planId}", planId), OWNER_TOKEN)
                .andExpect(status().isOk())
                .andReturn();
        var windowId = UUID.fromString(body(plan)
                .path("requirements").path("candidateWindows").get(0).path("id").asText());

        var finalizationCreated = request(post("/api/v1/plans/{planId}/requirement-finalization", planId)
                        .header("Idempotency-Key", "ui-discovery-finalize")
                        .header(HttpHeaders.IF_MATCH, "\"1\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"candidateWindowId":"%s","offerDeadline":"%s"}
                                """.formatted(windowId, startAt.minusHours(2)).strip()), OWNER_TOKEN)
                .andExpect(status().isCreated())
                .andReturn();
        var finalizationId = uuid(finalizationCreated, "finalizationId");

        var providerCreated = request(post("/api/v1/providers")
                        .header("Idempotency-Key", "ui-discovery-create-provider")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"displayName":"UI Discovery Courts","supportedCategories":["COURT"],
                                "serviceAreaCodes":["BGC"]}
                                """.replaceAll("\\R", "")), PROVIDER_TOKEN)
                .andExpect(status().isCreated())
                .andReturn();
        var providerId = uuid(providerCreated, "providerId");
        var submissionCreated = request(post(
                        "/api/v1/providers/{providerId}/verification-submissions", providerId)
                        .header("Idempotency-Key", "ui-discovery-submit-verification")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"evidenceReferences\":[\"registry:ui-discovery\"]}"), PROVIDER_TOKEN)
                .andExpect(status().isOk())
                .andReturn();
        var submissionId = uuid(submissionCreated, "submissionId");

        var groups = request(get("/api/v1/groups").param("limit", "1"), OWNER_TOKEN)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].groupId").value(groupId.toString()))
                .andExpect(jsonPath("$.items[0].role").value("ORGANIZER"))
                .andReturn();
        assertFields(body(groups).path("items").get(0), GROUP_FIELDS);
        request(get("/api/v1/groups"), OUTSIDER_TOKEN)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isEmpty());
        assertPrivateNotFound(get("/api/v1/groups/{groupId}", groupId), OUTSIDER_TOKEN);

        var providers = request(get("/api/v1/providers").param("limit", "1"), PROVIDER_TOKEN)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].providerId").value(providerId.toString()))
                .andExpect(jsonPath("$.items[0].callerStaffRole").value("ADMIN"))
                .andReturn();
        assertFields(body(providers).path("items").get(0), PROVIDER_FIELDS);
        request(get("/api/v1/providers"), OWNER_TOKEN)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isEmpty());
        assertPrivateNotFound(get("/api/v1/providers/{providerId}", providerId), OWNER_TOKEN);

        var finalizations = request(get(
                        "/api/v1/plans/{planId}/requirement-finalizations", planId)
                        .param("limit", "1"), OWNER_TOKEN)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].finalizationId").value(finalizationId.toString()))
                .andExpect(jsonPath("$.items[0].currentBasis").value(true))
                .andReturn();
        assertFields(body(finalizations).path("items").get(0), FINALIZATION_FIELDS);
        assertPrivateNotFound(get("/api/v1/plans/{planId}/requirement-finalizations", planId)
                .param("cursor", "not-a-cursor"), PROVIDER_TOKEN);

        var queue = request(get("/api/v1/operations/providers/pending-verifications")
                        .param("limit", "1"), OPERATOR_TOKEN)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].submissionId").value(submissionId.toString()))
                .andExpect(jsonPath("$.items[0].providerId").value(providerId.toString()))
                .andExpect(jsonPath("$.items[0].evidenceReferences[0]")
                        .value("registry:ui-discovery"))
                .andReturn();
        assertFields(body(queue).path("items").get(0), VERIFICATION_FIELDS);
        request(get("/api/v1/operations/providers/pending-verifications")
                        .param("cursor", "not-a-cursor"), MEMBER_TOKEN)
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("FORBIDDEN_PLATFORM_ROLE"))
                .andExpect(jsonPath("$.submissionId").doesNotExist());
    }

    @Test
    void exposesStableUiDiscoveryReadsInExecutableOpenApi() throws Exception {
        var openApi = request(get("/v3/api-docs"), OWNER_TOKEN)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        var parseOptions = new ParseOptions();
        parseOptions.setResolve(false);
        var parsed = new OpenAPIV3Parser().readContents(openApi, null, parseOptions);
        assertThat(parsed.getOpenAPI()).isNotNull();
        assertThat(parsed.getMessages()).isEmpty();
        var root = objectMapper.readTree(openApi);

        var reads = List.of(
                new OpenApiRead(
                        "/api/v1/groups", "listGroups", "GroupPageRepresentation",
                        List.of("200", "400", "401")),
                new OpenApiRead(
                        "/api/v1/providers", "listProviders", "ProviderPageRepresentation",
                        List.of("200", "400", "401")),
                new OpenApiRead(
                        "/api/v1/plans/{planId}/requirement-finalizations",
                        "listRequirementFinalizations", "RequirementFinalizationPageRepresentation",
                        List.of("200", "400", "401", "404")),
                new OpenApiRead(
                        "/api/v1/operations/providers/pending-verifications",
                        "listPendingProviderVerifications", "PendingProviderVerificationPageRepresentation",
                        List.of("200", "400", "401", "403")));

        for (var read : reads) {
            var operation = root.path("paths").path(read.path()).path("get");
            assertThat(operation.isObject()).as("GET %s", read.path()).isTrue();
            assertThat(operation.path("operationId").asText()).isEqualTo(read.operationId());
            assertThat(operation.path("security").get(0).path("bearerAuth").isArray()).isTrue();
            assertThat(operation.path("responses").path("200").path("content")
                    .path("application/json").path("schema").path("$ref").asText())
                    .isEqualTo("#/components/schemas/" + read.responseSchema());
            assertQueryParameter(operation, "cursor", "string");
            assertQueryParameter(operation, "limit", "integer");
            for (var responseCode : read.responseCodes()) {
                var response = operation.path("responses").path(responseCode);
                assertThat(response.isObject())
                        .as("GET %s response %s", read.path(), responseCode)
                        .isTrue();
                if (responseCode.startsWith("4")) {
                    assertThat(response.path("content").path("application/problem+json")
                            .path("schema").path("$ref").asText())
                            .isEqualTo("#/components/schemas/ApiProblemResponse");
                }
            }
        }

        var codes = root.path("components").path("schemas").path("ApiProblemResponse")
                .path("properties").path("code").path("enum");
        assertThat(codes).extracting(JsonNode::asText).contains(
                "AUTHENTICATION_REQUIRED", "INVALID_CURSOR",
                "PRIVATE_RESOURCE_NOT_FOUND", "FORBIDDEN_PLATFORM_ROLE");
    }

    private void assertQueryParameter(JsonNode operation, String name, String type) {
        assertThat(operation.path("parameters")).anySatisfy(parameter -> {
            assertThat(parameter.path("name").asText()).isEqualTo(name);
            assertThat(parameter.path("in").asText()).isEqualTo("query");
            assertThat(parameter.path("required").asBoolean()).isFalse();
            assertThat(parameter.path("schema").path("type").asText()).isEqualTo(type);
        });
    }

    private void assertPrivateNotFound(MockHttpServletRequestBuilder builder, String token) throws Exception {
        request(builder, token)
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("PRIVATE_RESOURCE_NOT_FOUND"));
    }

    private void assertFields(JsonNode item, Set<String> fields) {
        assertThat(item.propertyNames()).containsExactlyInAnyOrderElementsOf(fields);
    }

    private org.springframework.test.web.servlet.ResultActions request(
            MockHttpServletRequestBuilder builder, String token) throws Exception {
        return mockMvc.perform(builder
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .header(CorrelationIdFilter.HEADER_NAME, CORRELATION_ID));
    }

    private UUID uuid(MvcResult result, String field) throws Exception {
        return UUID.fromString(body(result).path(field).asText());
    }

    private JsonNode body(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsByteArray());
    }

    private OffsetDateTime databaseNow() {
        return jdbcClient.sql("SELECT clock_timestamp()")
                .query(OffsetDateTime.class)
                .single()
                .truncatedTo(ChronoUnit.MICROS);
    }

    private String planRequest(OffsetDateTime startAt, OffsetDateTime endAt) {
        return """
                {"title":"UI discovery plan","category":"COURT","timeZone":"Asia/Manila",
                "candidateWindows":[{"startAt":"%s","endAt":"%s"}],
                "area":{"code":"BGC","radiusKm":5},"headcount":{"minimum":4,"maximum":10},
                "budget":{"currency":"PHP","minimumAmount":"1000.00","maximumAmount":"2500.00"},
                "mustHaves":["parking"],"providerSafeNotes":"Provider-safe UI fixture.",
                "categoryAttributes":{"hasParking":true}}
                """.formatted(startAt, endAt).replaceAll("\\R", "");
    }
}

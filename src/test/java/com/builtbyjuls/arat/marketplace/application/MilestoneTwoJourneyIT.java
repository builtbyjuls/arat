package com.builtbyjuls.arat.marketplace.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
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
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
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
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@ActiveProfiles({"test", "local"})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class MilestoneTwoJourneyIT extends PostgreSqlIntegrationTest {

    private static final String OWNER_TOKEN = "arat-local-owner-token";
    private static final String OUTSIDER_TOKEN = "arat-local-outsider-token";
    private static final String PROVIDER_TOKEN = "arat-local-provider-token";
    private static final String OPERATOR_TOKEN = "arat-local-operator-token";
    private static final String CORRELATION_ID = "m2-journey-123";

    private record OpenApiOperation(String path, String method, String operationId, List<String> responseCodes) {}

    private record OpenApiResponse(String path, String method, String status, String schema) {}

    private record OpenApiHeader(String path, String method, String status, String header) {}

    private record OpenApiRequestHeader(String path, String method, String header) {}

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
        jdbcClient.sql("""
                DELETE FROM identity_account
                WHERE account_id IN (:ownerId, :memberId, :outsiderId, :providerId, :operatorId)
                """)
                .param("ownerId", LocalAccountFixtures.OWNER.accountId())
                .param("memberId", LocalAccountFixtures.MEMBER.accountId())
                .param("outsiderId", LocalAccountFixtures.OUTSIDER.accountId())
                .param("providerId", LocalAccountFixtures.PROVIDER.accountId())
                .param("operatorId", LocalAccountFixtures.OPERATOR.accountId())
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
    void completesTheProviderRequestJourneyThroughLocalBearerHttp() throws Exception {
        var providerCreated = createProvider(
                PROVIDER_TOKEN,
                "m2-create-provider",
                "Journey Courts",
                "[\"KTV\"]",
                "[\"MAKATI\"]")
                .andExpect(status().isCreated())
                .andExpect(header().string(HttpHeaders.ETAG, "\"1\""))
                .andReturn();
        var providerReplay = createProvider(
                PROVIDER_TOKEN,
                "m2-create-provider",
                "Journey Courts",
                "[\"KTV\"]",
                "[\"MAKATI\"]")
                .andExpect(status().isCreated())
                .andExpect(header().string(HttpHeaders.ETAG, providerCreated.getResponse().getHeader(HttpHeaders.ETAG)))
                .andReturn();
        assertThat(providerReplay.getResponse().getContentAsString())
                .isEqualTo(providerCreated.getResponse().getContentAsString());
        var providerId = uuid(providerCreated, "providerId");
        var verifiedProviderEtag = verifyProvider(providerId, PROVIDER_TOKEN, "primary");

        var unrelatedCreated = createProvider(
                OUTSIDER_TOKEN,
                "m2-create-unrelated-provider",
                "Unrelated KTV",
                "[\"KTV\"]",
                "[\"MAKATI\"]")
                .andExpect(status().isCreated())
                .andReturn();
        var unrelatedProviderId = uuid(unrelatedCreated, "providerId");
        verifyProvider(unrelatedProviderId, OUTSIDER_TOKEN, "unrelated");

        var groupCreated = request(post("/api/v1/groups")
                        .header("Idempotency-Key", "m2-create-group")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"M2 badminton group\",\"description\":\"Private journey group\"}"), OWNER_TOKEN)
                .andExpect(status().isCreated())
                .andReturn();
        var groupId = uuid(groupCreated, "groupId");
        var startAt = databaseNow().plusDays(30).truncatedTo(ChronoUnit.MICROS);
        var endAt = startAt.plusHours(2);
        var planCreated = request(post("/api/v1/groups/{groupId}/plans", groupId)
                        .header("Idempotency-Key", "m2-create-plan")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(planRequest(startAt, endAt, "Initial provider-safe terms.")), OWNER_TOKEN)
                .andExpect(status().isCreated())
                .andExpect(header().string(HttpHeaders.ETAG, "\"1\""))
                .andReturn();
        var planId = uuid(planCreated, "planId");
        var planDetail = request(get("/api/v1/plans/{planId}", planId), OWNER_TOKEN)
                .andExpect(status().isOk())
                .andReturn();
        var windowId = UUID.fromString(objectMapper.readTree(planDetail.getResponse().getContentAsString())
                .path("requirements").path("candidateWindows").get(0).path("id").asText());

        var firstFinalization = finalizeRequirements(
                planId, windowId, startAt.minusHours(2), "m2-finalize-one", "\"1\"")
                .andExpect(status().isCreated())
                .andExpect(header().string(HttpHeaders.ETAG, "\"1\""))
                .andReturn();
        var firstFinalizationId = uuid(firstFinalization, "finalizationId");

        publish(planId, firstFinalizationId, "m2-zero-recipient-publication", "\"1\"")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("NO_ELIGIBLE_PROVIDERS"));

        var matchingProfile = providerRequest("Journey Courts", "[\"COURT\"]", "[\"BGC\"]");
        request(put("/api/v1/providers/{providerId}/profile", providerId)
                        .header(HttpHeaders.IF_MATCH, verifiedProviderEtag)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(matchingProfile), PROVIDER_TOKEN)
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ETAG, "\"4\""));

        var firstPublished = publish(planId, firstFinalizationId, "m2-publish-one", "\"1\"")
                .andExpect(status().isCreated())
                .andExpect(header().string(HttpHeaders.ETAG, "\"2\""))
                .andExpect(header().exists(HttpHeaders.LOCATION))
                .andExpect(jsonPath("$.requestVersion").value(1))
                .andExpect(jsonPath("$.state").value("OPEN"))
                .andExpect(jsonPath("$.actionable").value(true))
                .andExpect(jsonPath("$.providerSafeNotes").value("Initial provider-safe terms."))
                .andReturn();
        var firstRequestId = uuid(firstPublished, "requestId");

        var memberRead = request(get("/api/v1/plans/{planId}/published-requests/current", planId), OWNER_TOKEN)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requestId").value(firstRequestId.toString()))
                .andReturn();
        var providerRead = request(get(
                        "/api/v1/providers/{providerId}/published-requests/{requestId}",
                        providerId,
                        firstRequestId), PROVIDER_TOKEN)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requestId").value(firstRequestId.toString()))
                .andReturn();
        assertRepresentationAllowlists(memberRead, providerRead);
        request(get("/api/v1/providers/{providerId}/request-feed", providerId), PROVIDER_TOKEN)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].requestId").value(firstRequestId.toString()));
        request(get(
                        "/api/v1/providers/{providerId}/published-requests/{requestId}",
                        unrelatedProviderId,
                        firstRequestId), OUTSIDER_TOKEN)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PRIVATE_RESOURCE_NOT_FOUND"));
        request(get("/api/v1/providers/{providerId}/request-feed", unrelatedProviderId), OUTSIDER_TOKEN)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isEmpty());

        var replacementStart = startAt.plusDays(1);
        var replacementEnd = replacementStart.plusHours(2);
        var replacementBody = replacementRequest(
                windowId, replacementStart, replacementEnd, "Private revision after N.");
        request(put("/api/v1/plans/{planId}/requirements", planId)
                        .header(HttpHeaders.IF_MATCH, "\"1\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(replacementBody), OWNER_TOKEN)
                .andExpect(status().isPreconditionFailed())
                .andExpect(jsonPath("$.code").value("PRECONDITION_FAILED"));
        request(put("/api/v1/plans/{planId}/requirements", planId)
                        .header(HttpHeaders.IF_MATCH, "\"2\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(replacementBody), OWNER_TOKEN)
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ETAG, "\"3\""));
        request(get("/api/v1/plans/{planId}/published-requests/current", planId), OWNER_TOKEN)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requestId").value(firstRequestId.toString()))
                .andExpect(jsonPath("$.providerSafeNotes").value("Initial provider-safe terms."));

        var secondFinalization = finalizeRequirements(
                planId, windowId, replacementStart.minusHours(2), "m2-finalize-two", "\"3\"")
                .andExpect(status().isCreated())
                .andReturn();
        var secondPublished = publish(
                        planId, uuid(secondFinalization, "finalizationId"), "m2-publish-two", "\"3\"")
                .andExpect(status().isCreated())
                .andExpect(header().string(HttpHeaders.ETAG, "\"4\""))
                .andExpect(jsonPath("$.requestVersion").value(2))
                .andExpect(jsonPath("$.providerSafeNotes").value("Private revision after N."))
                .andReturn();
        var secondRequestId = uuid(secondPublished, "requestId");
        request(get("/api/v1/plans/{planId}/published-requests", planId), OWNER_TOKEN)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].requestId").value(secondRequestId.toString()))
                .andExpect(jsonPath("$.items[0].state").value("OPEN"))
                .andExpect(jsonPath("$.items[1].requestId").value(firstRequestId.toString()))
                .andExpect(jsonPath("$.items[1].state").value("SUPERSEDED"))
                .andExpect(jsonPath("$.items[1].providerSafeNotes").value("Initial provider-safe terms."));
        request(get(
                        "/api/v1/plans/{planId}/published-requests/{requestId}",
                        planId,
                        firstRequestId), OWNER_TOKEN)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("SUPERSEDED"));

        var firstReplay = publish(planId, firstFinalizationId, "m2-publish-one", "\"1\"")
                .andExpect(status().isCreated())
                .andExpect(header().string(HttpHeaders.ETAG, firstPublished.getResponse().getHeader(HttpHeaders.ETAG)))
                .andReturn();
        assertThat(firstReplay.getResponse().getContentAsString())
                .isEqualTo(firstPublished.getResponse().getContentAsString());

        request(post("/api/v1/operations/providers/{providerId}/suspension", providerId)
                        .header("Idempotency-Key", "m2-suspend-provider")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"Journey eligibility fence.\"}"), OPERATOR_TOKEN)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.verificationStatus").value("SUSPENDED"));
        assertProviderCannotRead(providerId, secondRequestId);
        request(post("/api/v1/operations/providers/{providerId}/restoration", providerId)
                        .header("Idempotency-Key", "m2-restore-provider"), OPERATOR_TOKEN)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.verificationStatus").value("VERIFIED"));
        assertProviderCannotRead(providerId, secondRequestId);

        var deadlineFinalization = finalizeRequirements(
                planId, windowId, databaseNow().plusSeconds(5), "m2-finalize-three", "\"4\"")
                .andExpect(status().isCreated())
                .andReturn();
        var thirdPublished = publish(
                        planId, uuid(deadlineFinalization, "finalizationId"), "m2-publish-three", "\"4\"")
                .andExpect(status().isCreated())
                .andExpect(header().string(HttpHeaders.ETAG, "\"5\""))
                .andExpect(jsonPath("$.requestVersion").value(3))
                .andExpect(jsonPath("$.actionable").value(true))
                .andReturn();
        var thirdRequestId = uuid(thirdPublished, "requestId");
        request(get(
                        "/api/v1/providers/{providerId}/published-requests/{requestId}",
                        providerId,
                        thirdRequestId), PROVIDER_TOKEN)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.actionable").value(true));
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> request(get(
                        "/api/v1/providers/{providerId}/published-requests/{requestId}",
                        providerId,
                        thirdRequestId), PROVIDER_TOKEN)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("OPEN"))
                .andExpect(jsonPath("$.actionable").value(false)));

        request(post("/api/v1/published-requests/{requestId}/closure", thirdRequestId)
                        .header("Idempotency-Key", "m2-close-request")
                        .header(HttpHeaders.IF_MATCH, "\"5\""), OWNER_TOKEN)
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ETAG, "\"6\""))
                .andExpect(jsonPath("$.state").value("CLOSED"))
                .andExpect(jsonPath("$.actionable").value(false));
        request(get("/api/v1/plans/{planId}", planId), OWNER_TOKEN)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("COLLABORATING"));

        assertThat(jdbcClient.sql("SELECT count(*) FROM messaging_outbox")
                .query(Long.class).single()).isEqualTo(6);
        assertThat(jdbcClient.sql("""
                        SELECT count(*) FROM messaging_outbox
                        WHERE delivery_status = 'PENDING' AND published_at IS NULL
                        """).query(Long.class).single()).isEqualTo(6);
    }

    @Test
    void exposesEveryM2RouteSchemaHeaderResponseAndProblemCodeInOpenApi() throws Exception {
        var openApi = request(get("/v3/api-docs"), OWNER_TOKEN)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        var parseOptions = new ParseOptions();
        parseOptions.setResolve(false);
        var parsed = new OpenAPIV3Parser().readContents(openApi, null, parseOptions);
        assertThat(parsed.getOpenAPI()).isNotNull();
        assertThat(parsed.getMessages()).isEmpty();
        var root = objectMapper.readTree(openApi);

        var fullCommandResponses = List.of("200", "400", "401", "403", "404", "409", "422");
        var versionedCommandResponses = List.of("200", "400", "401", "403", "404", "409", "412", "422", "428");
        var operations = List.of(
                new OpenApiOperation("/api/v1/providers", "post", "createProvider", List.of("201", "400", "401", "409", "422")),
                new OpenApiOperation("/api/v1/providers/{providerId}", "get", "readProvider", List.of("200", "401", "404")),
                new OpenApiOperation("/api/v1/providers/{providerId}/profile", "put", "replaceProviderProfile", List.of("200", "400", "401", "403", "404", "412", "422", "428")),
                new OpenApiOperation("/api/v1/providers/{providerId}/verification-submissions", "post", "submitProviderVerification", fullCommandResponses),
                new OpenApiOperation("/api/v1/operations/providers/{providerId}/verification-decisions", "post", "decideProviderVerification", fullCommandResponses),
                new OpenApiOperation("/api/v1/operations/providers/{providerId}/suspension", "post", "suspendProvider", fullCommandResponses),
                new OpenApiOperation("/api/v1/operations/providers/{providerId}/restoration", "post", "restoreProvider", fullCommandResponses),
                new OpenApiOperation("/api/v1/plans/{planId}/requirement-finalization", "post", "finalizePlanRequirements", List.of("201", "400", "401", "403", "404", "409", "412", "422", "428")),
                new OpenApiOperation("/api/v1/plans/{planId}/published-requests", "post", "publishProviderRequest", List.of("201", "400", "401", "403", "404", "409", "412", "422", "428")),
                new OpenApiOperation("/api/v1/plans/{planId}/published-requests/current", "get", "readCurrentGroupPublishedRequest", List.of("200", "401", "404")),
                new OpenApiOperation("/api/v1/plans/{planId}/published-requests/{requestId}", "get", "readGroupPublishedRequestHistoryItem", List.of("200", "401", "404")),
                new OpenApiOperation("/api/v1/plans/{planId}/published-requests", "get", "listGroupPublishedRequestHistory", List.of("200", "400", "401", "404")),
                new OpenApiOperation("/api/v1/providers/{providerId}/published-requests/{requestId}", "get", "readProviderPublishedRequest", List.of("200", "401", "404")),
                new OpenApiOperation("/api/v1/providers/{providerId}/request-feed", "get", "listProviderRequestFeed", List.of("200", "400", "401", "404")),
                new OpenApiOperation("/api/v1/published-requests/{requestId}/closure", "post", "closeProviderRequest", versionedCommandResponses),
                new OpenApiOperation("/api/v1/plans/{planId}/cancellation", "post", "cancelCollaborativePlan", versionedCommandResponses));
        for (var operation : operations) {
            var documented = root.path("paths").path(operation.path()).path(operation.method());
            assertThat(documented.path("operationId").asText()).isEqualTo(operation.operationId());
            assertThat(documented.path("security").get(0).path("bearerAuth").isArray()).isTrue();
            for (var responseCode : operation.responseCodes()) {
                var response = documented.path("responses").path(responseCode);
                assertThat(response.isObject()).as("%s %s response %s", operation.method(), operation.path(), responseCode).isTrue();
                if (responseCode.startsWith("4")) {
                    assertThat(response.path("content").path("application/problem+json")
                            .path("schema").path("$ref").asText())
                            .isEqualTo("#/components/schemas/ApiProblemResponse");
                }
            }
        }

        assertSuccessSchemas(root, List.of(
                new OpenApiResponse("/api/v1/providers", "post", "201", "ProviderRepresentation"),
                new OpenApiResponse("/api/v1/providers/{providerId}", "get", "200", "ProviderRepresentation"),
                new OpenApiResponse("/api/v1/providers/{providerId}/profile", "put", "200", "ProviderRepresentation"),
                new OpenApiResponse("/api/v1/providers/{providerId}/verification-submissions", "post", "200", "ProviderVerificationSubmissionRepresentation"),
                new OpenApiResponse("/api/v1/operations/providers/{providerId}/verification-decisions", "post", "200", "ProviderVerificationDecisionRepresentation"),
                new OpenApiResponse("/api/v1/operations/providers/{providerId}/suspension", "post", "200", "ProviderSuspensionRepresentation"),
                new OpenApiResponse("/api/v1/operations/providers/{providerId}/restoration", "post", "200", "ProviderRestorationRepresentation"),
                new OpenApiResponse("/api/v1/plans/{planId}/requirement-finalization", "post", "201", "RequirementFinalization"),
                new OpenApiResponse("/api/v1/plans/{planId}/published-requests", "post", "201", "PublishedRequestRepresentation"),
                new OpenApiResponse("/api/v1/plans/{planId}/published-requests/current", "get", "200", "GroupPublishedRequestRepresentation"),
                new OpenApiResponse("/api/v1/plans/{planId}/published-requests/{requestId}", "get", "200", "GroupPublishedRequestRepresentation"),
                new OpenApiResponse("/api/v1/plans/{planId}/published-requests", "get", "200", "PublishedRequestPageRepresentation"),
                new OpenApiResponse("/api/v1/providers/{providerId}/published-requests/{requestId}", "get", "200", "PublishedRequestRepresentation"),
                new OpenApiResponse("/api/v1/providers/{providerId}/request-feed", "get", "200", "ProviderRequestFeedRepresentation"),
                new OpenApiResponse("/api/v1/published-requests/{requestId}/closure", "post", "200", "PublishedRequestRepresentation"),
                new OpenApiResponse("/api/v1/plans/{planId}/cancellation", "post", "200", "PlanRepresentation")));

        assertHeaders(root, List.of(
                new OpenApiHeader("/api/v1/providers", "post", "201", "ETag"),
                new OpenApiHeader("/api/v1/providers", "post", "201", "Location"),
                new OpenApiHeader("/api/v1/providers/{providerId}", "get", "200", "ETag"),
                new OpenApiHeader("/api/v1/providers/{providerId}/profile", "put", "200", "ETag"),
                new OpenApiHeader("/api/v1/providers/{providerId}/verification-submissions", "post", "200", "ETag"),
                new OpenApiHeader("/api/v1/operations/providers/{providerId}/verification-decisions", "post", "200", "ETag"),
                new OpenApiHeader("/api/v1/operations/providers/{providerId}/suspension", "post", "200", "ETag"),
                new OpenApiHeader("/api/v1/operations/providers/{providerId}/restoration", "post", "200", "ETag"),
                new OpenApiHeader("/api/v1/plans/{planId}/requirement-finalization", "post", "201", "ETag"),
                new OpenApiHeader("/api/v1/plans/{planId}/requirement-finalization", "post", "201", "Location"),
                new OpenApiHeader("/api/v1/plans/{planId}/published-requests", "post", "201", "ETag"),
                new OpenApiHeader("/api/v1/plans/{planId}/published-requests", "post", "201", "Location"),
                new OpenApiHeader("/api/v1/published-requests/{requestId}/closure", "post", "200", "ETag"),
                new OpenApiHeader("/api/v1/plans/{planId}/cancellation", "post", "200", "ETag")));

        assertRequestHeaders(root, List.of(
                new OpenApiRequestHeader("/api/v1/providers", "post", "Idempotency-Key"),
                new OpenApiRequestHeader("/api/v1/providers/{providerId}/profile", "put", "If-Match"),
                new OpenApiRequestHeader("/api/v1/providers/{providerId}/verification-submissions", "post", "Idempotency-Key"),
                new OpenApiRequestHeader("/api/v1/operations/providers/{providerId}/verification-decisions", "post", "Idempotency-Key"),
                new OpenApiRequestHeader("/api/v1/operations/providers/{providerId}/suspension", "post", "Idempotency-Key"),
                new OpenApiRequestHeader("/api/v1/operations/providers/{providerId}/restoration", "post", "Idempotency-Key"),
                new OpenApiRequestHeader("/api/v1/plans/{planId}/requirement-finalization", "post", "Idempotency-Key"),
                new OpenApiRequestHeader("/api/v1/plans/{planId}/requirement-finalization", "post", "If-Match"),
                new OpenApiRequestHeader("/api/v1/plans/{planId}/published-requests", "post", "Idempotency-Key"),
                new OpenApiRequestHeader("/api/v1/plans/{planId}/published-requests", "post", "If-Match"),
                new OpenApiRequestHeader("/api/v1/published-requests/{requestId}/closure", "post", "Idempotency-Key"),
                new OpenApiRequestHeader("/api/v1/published-requests/{requestId}/closure", "post", "If-Match"),
                new OpenApiRequestHeader("/api/v1/plans/{planId}/cancellation", "post", "Idempotency-Key"),
                new OpenApiRequestHeader("/api/v1/plans/{planId}/cancellation", "post", "If-Match")));

        var codes = root.path("components").path("schemas").path("ApiProblemResponse")
                .path("properties").path("code").path("enum");
        for (var code : List.of(
                "AUTHENTICATION_REQUIRED", "MALFORMED_REQUEST", "VALIDATION_FAILED",
                "PRIVATE_RESOURCE_NOT_FOUND", "FORBIDDEN_ROLE", "FORBIDDEN_PLATFORM_ROLE",
                "PRECONDITION_REQUIRED", "INVALID_PRECONDITION", "PRECONDITION_FAILED",
                "IDEMPOTENCY_KEY_REUSED", "INVALID_CURSOR", "INVALID_PLAN_STATE",
                "INVALID_PROVIDER_STATE", "FINALIZATION_VERSION_CHANGED",
                "NO_ELIGIBLE_PROVIDERS", "RECIPIENT_LIMIT_EXCEEDED",
                "REQUEST_DEADLINE_EXPIRED", "INVALID_REQUEST_STATE")) {
            assertThat(codes).extracting(JsonNode::asText).contains(code);
        }
    }

    private String verifyProvider(UUID providerId, String providerToken, String keyPrefix) throws Exception {
        var submission = request(post("/api/v1/providers/{providerId}/verification-submissions", providerId)
                        .header("Idempotency-Key", "m2-" + keyPrefix + "-submission")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"evidenceReferences\":[\"registry:" + keyPrefix + "\"]}"), providerToken)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.evidenceCount").value(1))
                .andReturn();
        var submissionId = uuid(submission, "submissionId");
        var decision = request(post("/api/v1/operations/providers/{providerId}/verification-decisions", providerId)
                        .header("Idempotency-Key", "m2-" + keyPrefix + "-decision")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"submissionId":"%s","decision":"ACCEPT","note":"Journey evidence reviewed."}
                                """.formatted(submissionId).strip()), OPERATOR_TOKEN)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.verificationStatus").value("VERIFIED"))
                .andReturn();
        return decision.getResponse().getHeader(HttpHeaders.ETAG);
    }

    private org.springframework.test.web.servlet.ResultActions createProvider(
            String token, String key, String name, String categories, String areaCodes) throws Exception {
        return request(post("/api/v1/providers")
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(providerRequest(name, categories, areaCodes)), token);
    }

    private String providerRequest(String name, String categories, String areaCodes) {
        return """
                {"displayName":"%s","supportedCategories":%s,"serviceAreaCodes":%s}
                """.formatted(name, categories, areaCodes).strip();
    }

    private org.springframework.test.web.servlet.ResultActions finalizeRequirements(
            UUID planId, UUID windowId, OffsetDateTime deadline, String key, String etag) throws Exception {
        return request(post("/api/v1/plans/{planId}/requirement-finalization", planId)
                        .header("Idempotency-Key", key)
                        .header(HttpHeaders.IF_MATCH, etag)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"candidateWindowId":"%s","offerDeadline":"%s"}
                                """.formatted(windowId, deadline).strip()), OWNER_TOKEN);
    }

    private org.springframework.test.web.servlet.ResultActions publish(
            UUID planId, UUID finalizationId, String key, String etag) throws Exception {
        return request(post("/api/v1/plans/{planId}/published-requests", planId)
                        .header("Idempotency-Key", key)
                        .header(HttpHeaders.IF_MATCH, etag)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"finalizationId\":\"" + finalizationId + "\"}"), OWNER_TOKEN);
    }

    private void assertRepresentationAllowlists(MvcResult memberRead, MvcResult providerRead) throws Exception {
        var member = objectMapper.readTree(memberRead.getResponse().getContentAsString());
        var provider = objectMapper.readTree(providerRead.getResponse().getContentAsString());
        assertThat(member.has("closedAt")).isTrue();
        assertThat(provider.has("closedAt")).isFalse();
        for (var privateField : List.of(
                "groupId", "groupName", "planId", "planTitle", "createdByAccountId",
                "members", "preferences", "attendance", "privateNote", "contact")) {
            assertThat(member.has(privateField)).isFalse();
            assertThat(provider.has(privateField)).isFalse();
        }
    }

    private void assertProviderCannotRead(UUID providerId, UUID requestId) throws Exception {
        request(get(
                        "/api/v1/providers/{providerId}/published-requests/{requestId}",
                        providerId,
                        requestId), PROVIDER_TOKEN)
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("PRIVATE_RESOURCE_NOT_FOUND"));
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

    private void assertRequestHeaders(JsonNode root, List<OpenApiRequestHeader> headers) {
        for (var header : headers) {
            var parameters = root.path("paths").path(header.path()).path(header.method()).path("parameters");
            assertThat(parameters).anySatisfy(parameter -> {
                assertThat(parameter.path("name").asText()).isEqualTo(header.header());
                assertThat(parameter.path("in").asText()).isEqualTo("header");
                assertThat(parameter.path("required").asBoolean()).isTrue();
            });
        }
    }

    private org.springframework.test.web.servlet.ResultActions request(
            MockHttpServletRequestBuilder request, String token) throws Exception {
        return mockMvc.perform(request
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .header(CorrelationIdFilter.HEADER_NAME, CORRELATION_ID));
    }

    private UUID uuid(MvcResult result, String field) throws Exception {
        return UUID.fromString(objectMapper.readTree(result.getResponse().getContentAsString()).path(field).asText());
    }

    private OffsetDateTime databaseNow() {
        return jdbcClient.sql("SELECT clock_timestamp()")
                .query(OffsetDateTime.class)
                .single()
                .truncatedTo(ChronoUnit.MICROS);
    }

    private String planRequest(OffsetDateTime startAt, OffsetDateTime endAt, String notes) {
        return """
                {"title":"Friday badminton","category":"COURT","timeZone":"Asia/Manila",
                "candidateWindows":[{"startAt":"%s","endAt":"%s"}],
                "area":{"code":"BGC","radiusKm":5},"headcount":{"minimum":4,"maximum":10},
                "budget":{"currency":"PHP","minimumAmount":"1000.00","maximumAmount":"2500.00"},
                "mustHaves":["parking","shower"],"providerSafeNotes":"%s",
                "categoryAttributes":{"hasParking":true,"courtCount":2}}
                """.formatted(startAt, endAt, notes).replaceAll("\\R", "");
    }

    private String replacementRequest(
            UUID windowId, OffsetDateTime startAt, OffsetDateTime endAt, String notes) {
        return """
                {"title":"Friday badminton revised","category":"COURT","timeZone":"Asia/Manila",
                "candidateWindows":[{"id":"%s","startAt":"%s","endAt":"%s"}],
                "area":{"code":"BGC","radiusKm":5},"headcount":{"minimum":5,"maximum":12},
                "budget":{"currency":"PHP","minimumAmount":"1200.00","maximumAmount":"3000.00"},
                "mustHaves":["parking"],"providerSafeNotes":"%s",
                "categoryAttributes":{"hasParking":true}}
                """.formatted(windowId, startAt, endAt, notes).replaceAll("\\R", "");
    }
}

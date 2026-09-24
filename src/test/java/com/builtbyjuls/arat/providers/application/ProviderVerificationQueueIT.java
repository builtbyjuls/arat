package com.builtbyjuls.arat.providers.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.builtbyjuls.arat.PostgreSqlIntegrationTest;
import com.builtbyjuls.arat.identity.api.AuthenticatedActor;
import com.builtbyjuls.arat.identity.api.PlatformRole;
import com.builtbyjuls.arat.web.CorrelationIdFilter;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
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
class ProviderVerificationQueueIT extends PostgreSqlIntegrationTest {

    private static final Set<String> ITEM_FIELDS = Set.of(
            "submissionId", "providerId", "providerVersion", "displayName", "verificationStatus",
            "supportedCategories", "serviceAreaCodes", "submittedAt", "evidenceReferences");
    private static final UUID OPERATOR_ID = UUID.fromString("82000000-0000-4000-8000-000000000001");
    private static final UUID SECOND_OPERATOR_ID = UUID.fromString("82000000-0000-4000-8000-000000000002");
    private static final UUID NON_OPERATOR_ID = UUID.fromString("82000000-0000-4000-8000-000000000003");
    private static final UUID ADMIN_ID = UUID.fromString("82000000-0000-4000-8000-000000000004");

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
        truncateProviderTables();
        jdbcClient.sql("DELETE FROM identity_account WHERE account_id IN (:operator, :secondOperator, :nonOperator, :admin)")
                .param("operator", OPERATOR_ID)
                .param("secondOperator", SECOND_OPERATOR_ID)
                .param("nonOperator", NON_OPERATOR_ID)
                .param("admin", ADMIN_ID)
                .update();
        insertAccount(OPERATOR_ID, "Queue operator");
        insertAccount(SECOND_OPERATOR_ID, "Second queue operator");
        insertAccount(NON_OPERATOR_ID, "Queue non-operator");
        insertAccount(ADMIN_ID, "Queue provider administrator");
        transactions = new TransactionTemplate(transactionManager);
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(correlationIdFilter)
                .apply(springSecurity())
                .build();
    }

    @AfterEach
    void cleanDatabase() {
        jdbcClient.sql("DELETE FROM audit_event").update();
        jdbcClient.sql("DELETE FROM idempotency_record").update();
        truncateProviderTables();
        jdbcClient.sql("DELETE FROM identity_account WHERE account_id IN (:operator, :secondOperator, :nonOperator, :admin)")
                .param("operator", OPERATOR_ID)
                .param("secondOperator", SECOND_OPERATOR_ID)
                .param("nonOperator", NON_OPERATOR_ID)
                .param("admin", ADMIN_ID)
                .update();
    }

    @Test
    void returnsOnlyTheBoundedSafeCurrentSubmissionProjection() throws Exception {
        queue(OPERATOR_ID, null, null).andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isEmpty())
                .andExpect(jsonPath("$.nextCursor").value(org.hamcrest.Matchers.nullValue()));

        var providerId = uuid("70000000-0000-4000-8000-000000000101");
        var submissionId = uuid("71000000-0000-4000-8000-000000000101");
        insertPendingProvider(
                providerId,
                submissionId,
                "Review venues",
                7,
                "2026-01-02T03:04:05Z",
                List.of("COURT", "KTV"),
                List.of("BGC", "MAKATI"),
                List.of("evidence:first", "evidence:second"));

        var result = queue(OPERATOR_ID, null, null).andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].submissionId").value(submissionId.toString()))
                .andExpect(jsonPath("$.items[0].providerId").value(providerId.toString()))
                .andExpect(jsonPath("$.items[0].providerVersion").value(7))
                .andExpect(jsonPath("$.items[0].displayName").value("Review venues"))
                .andExpect(jsonPath("$.items[0].verificationStatus").value("PENDING"))
                .andExpect(jsonPath("$.items[0].supportedCategories[0]").value("COURT"))
                .andExpect(jsonPath("$.items[0].supportedCategories[1]").value("KTV"))
                .andExpect(jsonPath("$.items[0].serviceAreaCodes[0]").value("BGC"))
                .andExpect(jsonPath("$.items[0].serviceAreaCodes[1]").value("MAKATI"))
                .andExpect(jsonPath("$.items[0].submittedAt").value("2026-01-02T03:04:05Z"))
                .andExpect(jsonPath("$.items[0].evidenceReferences[0]").value("evidence:first"))
                .andExpect(jsonPath("$.items[0].evidenceReferences[1]").value("evidence:second"))
                .andReturn();

        var item = objectMapper.readTree(result.getResponse().getContentAsByteArray()).path("items").get(0);
        assertThat(item.propertyNames()).containsOnlyElementsOf(ITEM_FIELDS);
        assertThat(result.getResponse().getContentAsString())
                .doesNotContain(ADMIN_ID.toString(), "eligibilityVersion", "audit", "decision");
    }

    @Test
    void pagesByStableSubmissionTimeAndIdWithActorBoundCursors() throws Exception {
        var oldestProvider = uuid("70000000-0000-4000-8000-000000000111");
        var middleProvider = uuid("70000000-0000-4000-8000-000000000112");
        var newestProvider = uuid("70000000-0000-4000-8000-000000000113");
        var oldest = uuid("71000000-0000-4000-8000-000000000111");
        var middle = uuid("71000000-0000-4000-8000-000000000112");
        var newest = uuid("71000000-0000-4000-8000-000000000113");
        insertPendingProvider(oldestProvider, oldest, "Oldest", 2, "2026-01-01T00:00:00Z");
        insertPendingProvider(middleProvider, middle, "Middle", 2, "2026-01-01T00:00:00Z");
        insertPendingProvider(newestProvider, newest, "Newest", 2, "2026-01-02T00:00:00Z");

        var first = queue(OPERATOR_ID, null, 2).andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].submissionId").value(newest.toString()))
                .andExpect(jsonPath("$.items[1].submissionId").value(middle.toString()))
                .andReturn();
        var cursor = body(first).path("nextCursor").asText();
        assertThat(cursor).isNotBlank();

        jdbcClient.sql("UPDATE provider_organization SET display_name = 'Updated oldest', version = 3 WHERE provider_id = :providerId")
                .param("providerId", oldestProvider)
                .update();
        queue(OPERATOR_ID, cursor, 2).andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].submissionId").value(oldest.toString()))
                .andExpect(jsonPath("$.items[0].displayName").value("Updated oldest"))
                .andExpect(jsonPath("$.items[0].providerVersion").value(3))
                .andExpect(jsonPath("$.nextCursor").value(org.hamcrest.Matchers.nullValue()));
        queue(SECOND_OPERATOR_ID, cursor, 2).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_CURSOR"));
    }

    @Test
    void authorizesBeforeCursorValidationWithoutLeakingRows() throws Exception {
        insertPendingProvider(
                uuid("70000000-0000-4000-8000-000000000121"),
                uuid("71000000-0000-4000-8000-000000000121"),
                "Hidden queue provider",
                2,
                "2026-01-01T00:00:00Z");

        var forbidden = queue(NON_OPERATOR_ID, "not-a-cursor", 20)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN_PLATFORM_ROLE"))
                .andReturn();
        assertThat(forbidden.getResponse().getContentAsString())
                .doesNotContain("Hidden queue provider", "evidence-reference");
        mockMvc.perform(get("/api/v1/operations/providers/pending-verifications"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void rejectsMalformedLimitsAndCursorPayloads() throws Exception {
        insertPendingProvider(
                uuid("70000000-0000-4000-8000-000000000131"),
                uuid("71000000-0000-4000-8000-000000000131"),
                "Cursor one",
                2,
                "2026-01-02T00:00:00Z");
        insertPendingProvider(
                uuid("70000000-0000-4000-8000-000000000132"),
                uuid("71000000-0000-4000-8000-000000000132"),
                "Cursor two",
                2,
                "2026-01-01T00:00:00Z");

        for (var cursor : List.of(
                "bad=cursor",
                encoded("null"),
                encoded("{\"version\":2}"),
                encoded("{\"version\":1,\"actorId\":\"" + OPERATOR_ID
                        + "\",\"submittedAt\":\"not-a-timestamp\",\"submissionId\":\""
                        + UUID.randomUUID() + "\"}"))) {
            queue(OPERATOR_ID, cursor, 20).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_CURSOR"));
        }
        queue(OPERATOR_ID, null, 0).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_CURSOR"));
        queue(OPERATOR_ID, null, 101).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_CURSOR"));
    }

    @Test
    void excludesSupersededAndAlreadyDecidedSubmissions() throws Exception {
        var currentProvider = uuid("70000000-0000-4000-8000-000000000141");
        var currentSubmission = uuid("71000000-0000-4000-8000-000000000141");
        var supersededSubmission = uuid("71000000-0000-4000-8000-000000000142");
        insertPendingProvider(currentProvider, currentSubmission, "Current provider", 4, "2026-01-02T00:00:00Z");
        insertSubmission(currentProvider, supersededSubmission, "2026-01-03T00:00:00Z", List.of("superseded-evidence"));
        insertDecision(currentProvider, supersededSubmission, "72000000-0000-4000-8000-000000000142");

        var decidedProvider = uuid("70000000-0000-4000-8000-000000000143");
        var decidedSubmission = uuid("71000000-0000-4000-8000-000000000143");
        insertDecidedProvider(decidedProvider, decidedSubmission, "2026-01-04T00:00:00Z");
        insertDecision(decidedProvider, decidedSubmission, "72000000-0000-4000-8000-000000000143");

        var response = queue(OPERATOR_ID, null, 20).andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].submissionId").value(currentSubmission.toString()))
                .andReturn().getResponse().getContentAsString();
        assertThat(response).doesNotContain(
                supersededSubmission.toString(), decidedSubmission.toString(), "superseded-evidence", "decided-evidence");
    }

    @Test
    void aCommittedDecisionIsAbsentFromTheNextCursorPage() throws Exception {
        var newestProvider = uuid("70000000-0000-4000-8000-000000000151");
        var decidedProvider = uuid("70000000-0000-4000-8000-000000000152");
        var oldestProvider = uuid("70000000-0000-4000-8000-000000000153");
        var newestSubmission = uuid("71000000-0000-4000-8000-000000000151");
        var decidedSubmission = uuid("71000000-0000-4000-8000-000000000152");
        var oldestSubmission = uuid("71000000-0000-4000-8000-000000000153");
        insertPendingProvider(newestProvider, newestSubmission, "Newest queue row", 2, "2026-01-03T00:00:00Z");
        insertPendingProvider(decidedProvider, decidedSubmission, "Decided queue row", 2, "2026-01-02T00:00:00Z");
        insertPendingProvider(oldestProvider, oldestSubmission, "Oldest queue row", 2, "2026-01-01T00:00:00Z");

        var first = queue(OPERATOR_ID, null, 1).andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].submissionId").value(newestSubmission.toString()))
                .andReturn();
        var cursor = body(first).path("nextCursor").asText();
        mockMvc.perform(post("/api/v1/operations/providers/{providerId}/verification-decisions", decidedProvider)
                        .with(authentication(authenticationFor(OPERATOR_ID, true)))
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"submissionId\":\"%s\",\"decision\":\"ACCEPT\"}".formatted(decidedSubmission))
                        .header("Idempotency-Key", "decide-while-paging"))
                .andExpect(status().isOk());

        var next = queue(OPERATOR_ID, cursor, 20).andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].submissionId").value(oldestSubmission.toString()))
                .andReturn().getResponse().getContentAsString();
        assertThat(next).doesNotContain(decidedSubmission.toString(), "Decided queue row");
    }

    @Test
    void usesTheQueueIndexAndPublishesTheOpenApiContract() throws Exception {
        insertPendingProvider(
                uuid("70000000-0000-4000-8000-000000000161"),
                uuid("71000000-0000-4000-8000-000000000161"),
                "Indexed provider",
                2,
                "2026-01-01T00:00:00Z");
        List<String> plan = transactions.execute(status -> {
            jdbcClient.sql("SET LOCAL enable_seqscan = off").update();
            return jdbcClient.sql("""
                            EXPLAIN (COSTS OFF)
                            SELECT submission_id
                            FROM provider_verification_submission
                            ORDER BY submitted_at DESC, submission_id DESC
                            LIMIT 21
                            """)
                    .query(String.class)
                    .list();
        });
        assertThat(plan).anyMatch(line -> line.contains("provider_verification_submission_queue_idx"));

        mockMvc.perform(get("/v3/api-docs")).andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/v1/operations/providers/pending-verifications'].get.operationId")
                        .value("listPendingProviderVerifications"))
                .andExpect(jsonPath("$.paths['/api/v1/operations/providers/pending-verifications'].get.responses['200'].content['application/json'].schema.$ref")
                        .value("#/components/schemas/PendingProviderVerificationPageRepresentation"))
                .andExpect(jsonPath("$.paths['/api/v1/operations/providers/pending-verifications'].get.responses['403']")
                        .exists());
    }

    private ResultActions queue(UUID actorId, String cursor, Integer limit) throws Exception {
        var request = get("/api/v1/operations/providers/pending-verifications")
                .with(authentication(authenticationFor(actorId, actorId.equals(OPERATOR_ID) || actorId.equals(SECOND_OPERATOR_ID))));
        if (cursor != null) {
            request.param("cursor", cursor);
        }
        if (limit != null) {
            request.param("limit", limit.toString());
        }
        return mockMvc.perform(request);
    }

    private TestingAuthenticationToken authenticationFor(UUID actorId, boolean operator) {
        var roles = operator ? Set.of(PlatformRole.PLATFORM_OPERATOR) : Set.<PlatformRole>of();
        return new TestingAuthenticationToken(new AuthenticatedActor(actorId, roles), null, "ROLE_USER");
    }

    private void insertPendingProvider(
            UUID providerId, UUID submissionId, String displayName, long version, String submittedAt) {
        insertPendingProvider(
                providerId,
                submissionId,
                displayName,
                version,
                submittedAt,
                List.of("COURT"),
                List.of("BGC"),
                List.of("evidence-reference"));
    }

    private void insertPendingProvider(
            UUID providerId,
            UUID submissionId,
            String displayName,
            long version,
            String submittedAt,
            List<String> categories,
            List<String> areas,
            List<String> evidenceReferences) {
        transactions.executeWithoutResult(status -> {
            jdbcClient.sql("""
                            INSERT INTO provider_organization (
                                provider_id, display_name, verification_status, version, eligibility_version,
                                current_verification_submission_id
                            ) VALUES (:providerId, :displayName, 'PENDING', :version, :version, :submissionId)
                            """)
                    .param("providerId", providerId)
                    .param("displayName", displayName)
                    .param("version", version)
                    .param("submissionId", submissionId)
                    .update();
            insertProfile(providerId, categories, areas);
            insertSubmissionRows(providerId, submissionId, submittedAt, evidenceReferences);
        });
    }

    private void insertDecidedProvider(UUID providerId, UUID submissionId, String submittedAt) {
        transactions.executeWithoutResult(status -> {
            jdbcClient.sql("""
                            INSERT INTO provider_organization (
                                provider_id, display_name, verification_status, version, eligibility_version
                            ) VALUES (:providerId, 'Already decided provider', 'VERIFIED', 3, 3)
                            """)
                    .param("providerId", providerId)
                    .update();
            insertProfile(providerId, List.of("COURT"), List.of("BGC"));
            insertSubmissionRows(providerId, submissionId, submittedAt, List.of("decided-evidence"));
        });
    }

    private void insertProfile(UUID providerId, List<String> categories, List<String> areas) {
        categories.forEach(category -> jdbcClient.sql("INSERT INTO provider_supported_category (provider_id, category) VALUES (:providerId, :category)")
                .param("providerId", providerId).param("category", category).update());
        areas.forEach(area -> jdbcClient.sql("INSERT INTO provider_service_area (provider_id, area_code) VALUES (:providerId, :area)")
                .param("providerId", providerId).param("area", area).update());
    }

    private void insertSubmission(
            UUID providerId, UUID submissionId, String submittedAt, List<String> evidenceReferences) {
        transactions.executeWithoutResult(status ->
                insertSubmissionRows(providerId, submissionId, submittedAt, evidenceReferences));
    }

    private void insertSubmissionRows(
            UUID providerId, UUID submissionId, String submittedAt, List<String> evidenceReferences) {
        jdbcClient.sql("""
                        INSERT INTO provider_verification_submission (
                            submission_id, provider_id, submitted_by_account_id, evidence_count, submitted_at
                        ) VALUES (:submissionId, :providerId, :adminId, :evidenceCount, :submittedAt)
                        """)
                .param("submissionId", submissionId)
                .param("providerId", providerId)
                .param("adminId", ADMIN_ID)
                .param("evidenceCount", evidenceReferences.size())
                .param("submittedAt", OffsetDateTime.parse(submittedAt))
                .update();
        for (var index = 0; index < evidenceReferences.size(); index++) {
            jdbcClient.sql("""
                            INSERT INTO provider_verification_evidence_reference (
                                submission_id, sort_order, evidence_reference
                            ) VALUES (:submissionId, :sortOrder, :evidenceReference)
                            """)
                    .param("submissionId", submissionId)
                    .param("sortOrder", index + 1)
                    .param("evidenceReference", evidenceReferences.get(index))
                    .update();
        }
    }

    private void insertDecision(UUID providerId, UUID submissionId, String decisionId) {
        jdbcClient.sql("""
                        INSERT INTO provider_verification_decision (
                            decision_id, provider_id, submission_id, decided_by_account_id, decision
                        ) VALUES (:decisionId, :providerId, :submissionId, :operatorId, 'REJECT')
                        """)
                .param("decisionId", uuid(decisionId))
                .param("providerId", providerId)
                .param("submissionId", submissionId)
                .param("operatorId", OPERATOR_ID)
                .update();
    }

    private void truncateProviderTables() {
        jdbcClient.sql("""
                TRUNCATE TABLE marketplace_request_recipient,
                    provider_suspension,
                    provider_verification_decision,
                    provider_verification_evidence_reference,
                    provider_verification_submission,
                    provider_service_area,
                    provider_supported_category,
                    provider_staff_membership,
                    provider_organization
                """).update();
    }

    private void insertAccount(UUID accountId, String displayName) {
        jdbcClient.sql("INSERT INTO identity_account (account_id, display_name) VALUES (:accountId, :displayName)")
                .param("accountId", accountId)
                .param("displayName", displayName)
                .update();
    }

    private JsonNode body(org.springframework.test.web.servlet.MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsByteArray());
    }

    private String encoded(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private UUID uuid(String value) {
        return UUID.fromString(value);
    }
}

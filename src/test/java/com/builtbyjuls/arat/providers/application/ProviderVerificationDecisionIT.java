package com.builtbyjuls.arat.providers.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.builtbyjuls.arat.PostgreSqlIntegrationTest;
import com.builtbyjuls.arat.identity.api.AuthenticatedActor;
import com.builtbyjuls.arat.identity.api.PlatformRole;
import com.builtbyjuls.arat.providers.api.DecideProviderVerificationCommand;
import com.builtbyjuls.arat.providers.api.ProviderVerificationDecisionException;
import com.builtbyjuls.arat.providers.domain.ProviderVerificationDecision;
import com.builtbyjuls.arat.testing.ConcurrentDatabaseWorkers;
import com.builtbyjuls.arat.web.CorrelationIdFilter;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;

@SpringBootTest
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ProviderVerificationDecisionIT extends PostgreSqlIntegrationTest {

    private static final UUID PROVIDER_ID = UUID.fromString("70000000-0000-4000-8000-000000000031");
    private static final UUID SUBMISSION_ID = UUID.fromString("71000000-0000-4000-8000-000000000031");
    private static final UUID OPERATOR_ID = UUID.fromString("80000000-0000-4000-8000-000000000031");
    private static final UUID ADMIN_ID = UUID.fromString("80000000-0000-4000-8000-000000000032");
    private static final UUID OUTSIDER_ID = UUID.fromString("80000000-0000-4000-8000-000000000033");
    private static final String CORRELATION_ID = "provider-decision-test-123";

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private CorrelationIdFilter correlationIdFilter;

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private ProviderVerificationDecisionService decisionService;

    private MockMvc mockMvc;
    private TransactionTemplate transactionTemplate;

    @BeforeEach
    void prepareDatabase() {
        removeAuditFailureTrigger();
        jdbcClient.sql("DELETE FROM audit_event").update();
        jdbcClient.sql("DELETE FROM idempotency_record").update();
        jdbcClient.sql("DELETE FROM identity_account WHERE account_id IN (:operatorId, :adminId, :outsiderId)")
                .param("operatorId", OPERATOR_ID)
                .param("adminId", ADMIN_ID)
                .param("outsiderId", OUTSIDER_ID)
                .update();
        insertAccount(OPERATOR_ID, "Platform operator");
        insertAccount(ADMIN_ID, "Provider administrator");
        insertAccount(OUTSIDER_ID, "Provider outsider");
        transactionTemplate = new TransactionTemplate(transactionManager);
        seedPendingProvider();
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .addFilters(correlationIdFilter)
                .apply(springSecurity())
                .build();
    }

    @AfterEach
    void removeAuditFailureTrigger() {
        jdbcClient.sql("DROP TRIGGER IF EXISTS fail_provider_decision_audit ON audit_event").update();
        jdbcClient.sql("DROP FUNCTION IF EXISTS test_fail_provider_decision_audit()").update();
        truncateProviderTables();
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

    @Test
    void operatorAcceptsCurrentSubmissionAndPersistsOneSanitizedDecision() throws Exception {
        var response = decideAsOperator("accept-provider", """
                {"decision":"ACCEPT","note":"  Organization details reviewed.  "}
                """)
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"3\""))
                .andExpect(jsonPath("$.providerId").value(PROVIDER_ID.toString()))
                .andExpect(jsonPath("$.submissionId").value(SUBMISSION_ID.toString()))
                .andExpect(jsonPath("$.decision").value("ACCEPT"))
                .andExpect(jsonPath("$.note").value("Organization details reviewed."))
                .andExpect(jsonPath("$.verificationStatus").value("VERIFIED"))
                .andExpect(jsonPath("$.providerVersion").value(3))
                .andExpect(jsonPath("$.eligibilityVersion").value(3))
                .andReturn().getResponse();

        assertThat(response.getContentAsString()).doesNotContain("opaque-evidence-reference");
        assertThat(providerStatus()).isEqualTo("VERIFIED");
        assertThat(providerVersion()).isEqualTo(3);
        assertThat(eligibilityVersion()).isEqualTo(3);
        assertThat(currentSubmissionId()).isNull();
        assertThat(decisionNote()).isEqualTo("Organization details reviewed.");
        assertThat(decisionSubmissionId()).isEqualTo(SUBMISSION_ID);
        assertThat(count("SELECT count(*) FROM provider_verification_decision")).isOne();
        assertThat(count("SELECT count(*) FROM audit_event WHERE action = 'provider.verification.accepted'")).isOne();
        assertThat(auditMetadata()).contains(PROVIDER_ID.toString(), SUBMISSION_ID.toString())
                .doesNotContain("Organization details reviewed", "opaque-evidence-reference");

        var decisionId = jdbcClient.sql("SELECT decision_id FROM provider_verification_decision")
                .query(UUID.class).single();
        assertThatThrownBy(() -> jdbcClient.sql("UPDATE provider_verification_decision SET decision_note = 'changed' WHERE decision_id = :decisionId")
                .param("decisionId", decisionId).update()).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbcClient.sql("DELETE FROM provider_verification_decision WHERE decision_id = :decisionId")
                .param("decisionId", decisionId).update()).isInstanceOf(DataAccessException.class);
    }

    @Test
    void operatorRejectsWithoutLeakingTheReasonThroughProviderProfile() throws Exception {
        decideAsOperator("reject-provider", """
                {"decision":"REJECT","note":"Contact evidence could not be confirmed."}
                """)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.decision").value("REJECT"))
                .andExpect(jsonPath("$.verificationStatus").value("REJECTED"));

        mockMvc.perform(get("/api/v1/providers/{providerId}", PROVIDER_ID)
                        .with(authentication(authenticationFor(ADMIN_ID, Set.of()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.verificationStatus").value("REJECTED"))
                .andExpect(jsonPath("$.note").doesNotExist())
                .andExpect(jsonPath("$.reason").doesNotExist());
        assertThat(decisionNote()).isEqualTo("Contact evidence could not be confirmed.");
        assertThat(count("SELECT count(*) FROM audit_event WHERE action = 'provider.verification.rejected'")).isOne();
    }

    @Test
    void requiresPlatformOperatorBeforeTargetLookupAndValidatesTheRequest() throws Exception {
        decideAs(ADMIN_ID, Set.of(), PROVIDER_ID, "provider-admin", "{\"decision\":\"ACCEPT\"}")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN_PLATFORM_ROLE"));
        decideAs(OUTSIDER_ID, Set.of(), UUID.randomUUID(), "outsider-missing", "{\"decision\":\"REJECT\"}")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN_PLATFORM_ROLE"));
        decideAs(OPERATOR_ID, Set.of(PlatformRole.PLATFORM_OPERATOR), UUID.randomUUID(), "operator-missing", "{\"decision\":\"ACCEPT\"}")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PRIVATE_RESOURCE_NOT_FOUND"));

        mockMvc.perform(post("/api/v1/operations/providers/{providerId}/verification-decisions", PROVIDER_ID)
                        .with(authentication(authenticationFor(OPERATOR_ID, Set.of(PlatformRole.PLATFORM_OPERATOR))))
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content(withSubmissionId(SUBMISSION_ID, "{\"decision\":\"ACCEPT\"}")))
                .andExpect(status().isBadRequest());
        decideAsOperator("blank-note", "{\"decision\":\"REJECT\",\"note\":\"   \"}")
                .andExpect(status().isUnprocessableEntity());
        decideAsOperator("unicode-blank-note", "{\"decision\":\"REJECT\",\"note\":\"\\u2003\"}")
                .andExpect(status().isUnprocessableEntity());
        decideAsOperator("long-note", "{\"decision\":\"REJECT\",\"note\":\"%s\"}".formatted("a".repeat(501)))
                .andExpect(status().isUnprocessableEntity());
        assertThat(providerStatus()).isEqualTo("PENDING");
        assertThat(count("SELECT count(*) FROM provider_verification_decision")).isZero();
        assertThat(count("SELECT count(*) FROM idempotency_record")).isZero();
    }

    @Test
    void allowsDecisionsOnlyFromPending() throws Exception {
        for (var state : List.of("UNVERIFIED", "VERIFIED", "REJECTED", "SUSPENDED")) {
            resetProviderState(state);
            decideAsOperator("wrong-state-" + state, "{\"decision\":\"ACCEPT\"}")
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("INVALID_PROVIDER_STATE"));
            assertThat(providerVersion()).isEqualTo(2);
            assertThat(eligibilityVersion()).isEqualTo(2);
        }
        assertThat(count("SELECT count(*) FROM provider_verification_decision")).isZero();
        assertThat(count("SELECT count(*) FROM audit_event")).isZero();
        assertThat(count("SELECT count(*) FROM idempotency_record")).isZero();
    }

    @Test
    void exactReplayReturnsOriginalDecisionWithoutAnotherVersionAdvance() throws Exception {
        var first = decideAsOperator("decision-replay", "{\"decision\":\"REJECT\",\"note\":\"Insufficient contact evidence.\"}")
                .andExpect(status().isOk()).andReturn().getResponse();
        var replay = decideAsOperator("decision-replay", "{\"decision\":\"REJECT\",\"note\":\"Insufficient contact evidence.\"}")
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", first.getHeader("ETag")))
                .andReturn().getResponse();
        assertThat(replay.getContentAsString()).isEqualTo(first.getContentAsString());

        decideAsOperator("decision-replay", "{\"decision\":\"ACCEPT\",\"note\":\"Insufficient contact evidence.\"}")
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(org.springframework.http.MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
        assertThat(providerVersion()).isEqualTo(3);
        assertThat(eligibilityVersion()).isEqualTo(3);
        assertThat(count("SELECT count(*) FROM provider_verification_decision")).isOne();
        assertThat(count("SELECT count(*) FROM audit_event")).isOne();
    }

    @Test
    void staleDecisionCannotDecideALaterResubmission() throws Exception {
        decideAsOperator("reject-first-submission", "{\"decision\":\"REJECT\",\"note\":\"Evidence was incomplete.\"}")
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/providers/{providerId}/verification-submissions", PROVIDER_ID)
                        .with(authentication(authenticationFor(ADMIN_ID, Set.of())))
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"evidenceReferences\":[\"replacement-evidence-reference\"]}")
                        .header("Idempotency-Key", "resubmit-after-rejection")
                        .header(CorrelationIdFilter.HEADER_NAME, CORRELATION_ID))
                .andExpect(status().isOk());
        var replacementSubmissionId = currentSubmissionId();
        assertThat(replacementSubmissionId).isNotEqualTo(SUBMISSION_ID);

        decideAsOperatorForSubmission(
                        SUBMISSION_ID, "stale-accept", "{\"decision\":\"ACCEPT\",\"note\":\"Delayed review.\"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_PROVIDER_STATE"));
        assertThat(providerStatus()).isEqualTo("PENDING");
        assertThat(currentSubmissionId()).isEqualTo(replacementSubmissionId);
        assertThat(providerVersion()).isEqualTo(4);
        assertThat(eligibilityVersion()).isEqualTo(4);

        decideAsOperatorForSubmission(
                        replacementSubmissionId, "accept-replacement", "{\"decision\":\"ACCEPT\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.submissionId").value(replacementSubmissionId.toString()));
        assertThat(providerStatus()).isEqualTo("VERIFIED");
        assertThat(providerVersion()).isEqualTo(5);
        assertThat(eligibilityVersion()).isEqualTo(5);
        assertThat(count("SELECT count(*) FROM provider_verification_decision")).isEqualTo(2);
    }

    @Test
    void concurrentOppositeDecisionsHaveOneWinnerInEitherLockOrder() throws Exception {
        assertOrderedDecisionRace(ProviderVerificationDecision.ACCEPT, ProviderVerificationDecision.REJECT);

        truncateProviderTables();
        jdbcClient.sql("DELETE FROM audit_event").update();
        jdbcClient.sql("DELETE FROM idempotency_record").update();
        seedPendingProvider();

        assertOrderedDecisionRace(ProviderVerificationDecision.REJECT, ProviderVerificationDecision.ACCEPT);
    }

    @Test
    void rollsBackTransitionDecisionAuditAndIdempotencyWhenAuditFails() throws Exception {
        jdbcClient.sql("""
                CREATE FUNCTION test_fail_provider_decision_audit()
                RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN RAISE EXCEPTION 'forced provider decision audit failure'; END;
                $$
                """).update();
        jdbcClient.sql("""
                CREATE TRIGGER fail_provider_decision_audit BEFORE INSERT ON audit_event
                FOR EACH ROW WHEN (NEW.action IN ('provider.verification.accepted', 'provider.verification.rejected'))
                EXECUTE FUNCTION test_fail_provider_decision_audit()
                """).update();

        decideAsOperator("decision-rollback", "{\"decision\":\"ACCEPT\"}")
                .andExpect(status().isInternalServerError());

        assertThat(providerStatus()).isEqualTo("PENDING");
        assertThat(providerVersion()).isEqualTo(2);
        assertThat(eligibilityVersion()).isEqualTo(2);
        assertThat(count("SELECT count(*) FROM provider_verification_decision")).isZero();
        assertThat(count("SELECT count(*) FROM audit_event")).isZero();
        assertThat(count("SELECT count(*) FROM idempotency_record")).isZero();
    }

    @Test
    void publishesTheOperatorDecisionRouteInExecutableOpenApi() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/v1/operations/providers/{providerId}/verification-decisions'].post.operationId")
                        .value("decideProviderVerification"))
                .andExpect(jsonPath("$.paths['/api/v1/operations/providers/{providerId}/verification-decisions'].post.parameters[?(@.name == 'Idempotency-Key')].required")
                        .value(true))
                .andExpect(jsonPath("$.paths['/api/v1/operations/providers/{providerId}/verification-decisions'].post.responses['200'].headers.ETag")
                        .exists())
                .andExpect(jsonPath("$.paths['/api/v1/operations/providers/{providerId}/verification-decisions'].post.responses['403']")
                        .exists());
    }

    private org.springframework.test.web.servlet.ResultActions decideAsOperator(String key, String body) throws Exception {
        return decideAsOperatorForSubmission(SUBMISSION_ID, key, body);
    }

    private org.springframework.test.web.servlet.ResultActions decideAsOperatorForSubmission(
            UUID submissionId, String key, String body) throws Exception {
        return decideAs(OPERATOR_ID, Set.of(PlatformRole.PLATFORM_OPERATOR), PROVIDER_ID, submissionId, key, body);
    }

    private org.springframework.test.web.servlet.ResultActions decideAs(
            UUID actorId, Set<PlatformRole> roles, UUID providerId, String key, String body) throws Exception {
        return decideAs(actorId, roles, providerId, SUBMISSION_ID, key, body);
    }

    private org.springframework.test.web.servlet.ResultActions decideAs(
            UUID actorId,
            Set<PlatformRole> roles,
            UUID providerId,
            UUID submissionId,
            String key,
            String body) throws Exception {
        return mockMvc.perform(post("/api/v1/operations/providers/{providerId}/verification-decisions", providerId)
                .with(authentication(authenticationFor(actorId, roles)))
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content(withSubmissionId(submissionId, body))
                .header("Idempotency-Key", key)
                .header(CorrelationIdFilter.HEADER_NAME, CORRELATION_ID));
    }

    private String withSubmissionId(UUID submissionId, String body) {
        return "{\"submissionId\":\"%s\",%s".formatted(submissionId, body.substring(1));
    }

    private void assertOrderedDecisionRace(
            ProviderVerificationDecision firstDecision,
            ProviderVerificationDecision secondDecision) throws Exception {
        var outcomes = ConcurrentDatabaseWorkers.runOrdered(
                dataSource,
                transactionManager,
                "SELECT provider_id FROM provider_organization WHERE provider_id = ? FOR NO KEY UPDATE",
                PROVIDER_ID,
                () -> decisionService.decide(decisionCommand("race-first-" + firstDecision, firstDecision)),
                () -> decisionService.decide(decisionCommand("race-second-" + secondDecision, secondDecision)));

        assertThat(outcomes.getFirst().succeeded()).isTrue();
        assertThat(outcomes.get(1).failure())
                .isInstanceOfSatisfying(ProviderVerificationDecisionException.class,
                        exception -> assertThat(exception.reason())
                                .isEqualTo(ProviderVerificationDecisionException.Reason.INVALID_PROVIDER_STATE));
        assertThat(providerStatus()).isEqualTo(
                firstDecision == ProviderVerificationDecision.ACCEPT ? "VERIFIED" : "REJECTED");
        assertThat(providerVersion()).isEqualTo(3);
        assertThat(eligibilityVersion()).isEqualTo(3);
        assertThat(count("SELECT count(*) FROM provider_verification_decision")).isOne();
        assertThat(count("SELECT count(*) FROM audit_event")).isOne();
        assertThat(count("SELECT count(*) FROM idempotency_record")).isOne();
    }

    private DecideProviderVerificationCommand decisionCommand(String key, ProviderVerificationDecision decision) {
        return new DecideProviderVerificationCommand(
                OPERATOR_ID,
                Set.of(PlatformRole.PLATFORM_OPERATOR),
                PROVIDER_ID,
                SUBMISSION_ID,
                key,
                decision,
                decision == ProviderVerificationDecision.REJECT ? "Evidence was not confirmed." : null,
                CORRELATION_ID);
    }

    private TestingAuthenticationToken authenticationFor(UUID accountId, Set<PlatformRole> roles) {
        return new TestingAuthenticationToken(new AuthenticatedActor(accountId, roles), null, "ROLE_USER");
    }

    private void seedPendingProvider() {
        transactionTemplate.executeWithoutResult(status -> {
            jdbcClient.sql("""
                    INSERT INTO provider_organization (
                        provider_id, display_name, verification_status, version, eligibility_version,
                        current_verification_submission_id
                    ) VALUES (:providerId, 'Provider venues', 'PENDING', 2, 2, :submissionId)
                    """)
                    .param("providerId", PROVIDER_ID)
                    .param("submissionId", SUBMISSION_ID)
                    .update();
            jdbcClient.sql("""
                    INSERT INTO provider_staff_membership (provider_id, account_id, role, status)
                    VALUES (:providerId, :adminId, 'ADMIN', 'ACTIVE')
                    """).param("providerId", PROVIDER_ID).param("adminId", ADMIN_ID).update();
            jdbcClient.sql("""
                    INSERT INTO provider_verification_submission (
                        submission_id, provider_id, submitted_by_account_id, evidence_count
                    ) VALUES (:submissionId, :providerId, :adminId, 1)
                    """)
                    .param("submissionId", SUBMISSION_ID)
                    .param("providerId", PROVIDER_ID)
                    .param("adminId", ADMIN_ID)
                    .update();
            jdbcClient.sql("""
                    INSERT INTO provider_verification_evidence_reference (
                        submission_id, sort_order, evidence_reference
                    ) VALUES (:submissionId, 1, 'opaque-evidence-reference')
                    """).param("submissionId", SUBMISSION_ID).update();
        });
    }

    private void resetProviderState(String state) {
        jdbcClient.sql("""
                UPDATE provider_organization
                SET verification_status = :state,
                    current_verification_submission_id = NULL,
                    version = 2,
                    eligibility_version = 2
                WHERE provider_id = :providerId
                """).param("state", state).param("providerId", PROVIDER_ID).update();
    }

    private void insertAccount(UUID accountId, String displayName) {
        jdbcClient.sql("INSERT INTO identity_account (account_id, display_name) VALUES (:accountId, :displayName)")
                .param("accountId", accountId).param("displayName", displayName).update();
    }

    private String providerStatus() {
        return jdbcClient.sql("SELECT verification_status FROM provider_organization WHERE provider_id = :providerId")
                .param("providerId", PROVIDER_ID).query(String.class).single();
    }

    private long providerVersion() {
        return jdbcClient.sql("SELECT version FROM provider_organization WHERE provider_id = :providerId")
                .param("providerId", PROVIDER_ID).query(Long.class).single();
    }

    private long eligibilityVersion() {
        return jdbcClient.sql("SELECT eligibility_version FROM provider_organization WHERE provider_id = :providerId")
                .param("providerId", PROVIDER_ID).query(Long.class).single();
    }

    private UUID currentSubmissionId() {
        return jdbcClient.sql("SELECT current_verification_submission_id FROM provider_organization WHERE provider_id = :providerId")
                .param("providerId", PROVIDER_ID).query(UUID.class).optional().orElse(null);
    }

    private String decisionNote() {
        return jdbcClient.sql("SELECT decision_note FROM provider_verification_decision")
                .query(String.class).single();
    }

    private UUID decisionSubmissionId() {
        return jdbcClient.sql("SELECT submission_id FROM provider_verification_decision")
                .query(UUID.class).single();
    }

    private String auditMetadata() {
        return jdbcClient.sql("SELECT metadata::TEXT FROM audit_event")
                .query(String.class).single();
    }

    private long count(String sql) {
        return jdbcClient.sql(sql).query(Long.class).single();
    }
}

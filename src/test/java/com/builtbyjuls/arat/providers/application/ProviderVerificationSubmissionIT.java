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
import com.builtbyjuls.arat.web.CorrelationIdFilter;
import java.util.Set;
import java.util.UUID;
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
import org.springframework.web.context.WebApplicationContext;

@SpringBootTest
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ProviderVerificationSubmissionIT extends PostgreSqlIntegrationTest {

    private static final UUID PROVIDER_ID = UUID.fromString("70000000-0000-4000-8000-000000000021");
    private static final UUID OTHER_PROVIDER_ID = UUID.fromString("70000000-0000-4000-8000-000000000022");
    private static final UUID ADMIN_ID = UUID.fromString("80000000-0000-4000-8000-000000000021");
    private static final UUID STAFF_ID = UUID.fromString("80000000-0000-4000-8000-000000000022");
    private static final UUID OTHER_ADMIN_ID = UUID.fromString("80000000-0000-4000-8000-000000000023");
    private static final UUID OUTSIDER_ID = UUID.fromString("80000000-0000-4000-8000-000000000024");
    private static final String CORRELATION_ID = "provider-verification-test-123";

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private CorrelationIdFilter correlationIdFilter;

    @Autowired
    private JdbcClient jdbcClient;

    private MockMvc mockMvc;

    @BeforeEach
    void prepareDatabase() {
        removeAuditFailureTrigger();
        jdbcClient.sql("DELETE FROM audit_event").update();
        jdbcClient.sql("DELETE FROM idempotency_record").update();
        jdbcClient.sql("TRUNCATE TABLE provider_verification_evidence_reference, provider_verification_submission").update();
        jdbcClient.sql("DELETE FROM provider_staff_membership").update();
        jdbcClient.sql("DELETE FROM provider_organization").update();
        jdbcClient.sql("DELETE FROM identity_account WHERE account_id IN (:adminId, :staffId, :otherAdminId, :outsiderId)")
                .param("adminId", ADMIN_ID)
                .param("staffId", STAFF_ID)
                .param("otherAdminId", OTHER_ADMIN_ID)
                .param("outsiderId", OUTSIDER_ID)
                .update();
        insertAccount(ADMIN_ID, "Provider administrator");
        insertAccount(STAFF_ID, "Provider staff member");
        insertAccount(OTHER_ADMIN_ID, "Other provider administrator");
        insertAccount(OUTSIDER_ID, "Provider outsider");
        insertProvider(PROVIDER_ID, "UNVERIFIED");
        insertProvider(OTHER_PROVIDER_ID, "UNVERIFIED");
        insertMembership(PROVIDER_ID, ADMIN_ID, "ADMIN", "ACTIVE");
        insertMembership(PROVIDER_ID, STAFF_ID, "STAFF", "ACTIVE");
        insertMembership(OTHER_PROVIDER_ID, OTHER_ADMIN_ID, "ADMIN", "ACTIVE");
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .addFilters(correlationIdFilter)
                .apply(springSecurity())
                .build();
    }

    @AfterEach
    void removeAuditFailureTrigger() {
        jdbcClient.sql("DROP TRIGGER IF EXISTS fail_provider_verification_audit ON audit_event").update();
        jdbcClient.sql("DROP FUNCTION IF EXISTS test_fail_provider_verification_audit()").update();
    }

    @Test
    void adminSubmitsAnImmutableBoundedEvidenceSetAndAdvancesBothVersionsOnce() throws Exception {
        var response = submitAs(ADMIN_ID, PROVIDER_ID, "verification-submit-1", "[\"opaque-ref-a\",\"opaque-ref-b\"]")
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"2\""))
                .andExpect(jsonPath("$.providerId").value(PROVIDER_ID.toString()))
                .andExpect(jsonPath("$.evidenceCount").value(2))
                .andExpect(jsonPath("$.providerVersion").value(2))
                .andReturn().getResponse();

        assertThat(response.getContentAsString()).doesNotContain("opaque-ref-a").doesNotContain("opaque-ref-b");
        assertThat(providerStatus()).isEqualTo("PENDING");
        assertThat(providerVersion()).isEqualTo(2);
        assertThat(eligibilityVersion()).isEqualTo(2);
        assertThat(count("SELECT count(*) FROM provider_verification_submission")).isOne();
        assertThat(evidenceReferences()).containsExactly("opaque-ref-a", "opaque-ref-b");
        assertThat(auditMetadata()).contains(PROVIDER_ID.toString()).doesNotContain("opaque-ref-a").doesNotContain("opaque-ref-b");
        assertThat(idempotencyReplayState()).doesNotContain("opaque-ref-a").doesNotContain("opaque-ref-b");

        var submissionId = jdbcClient.sql("SELECT submission_id FROM provider_verification_submission").query(UUID.class).single();
        assertThatThrownBy(() -> jdbcClient.sql("UPDATE provider_verification_submission SET evidence_count = 1 WHERE submission_id = :submissionId")
                .param("submissionId", submissionId).update()).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbcClient.sql("UPDATE provider_verification_evidence_reference SET evidence_reference = 'changed' WHERE submission_id = :submissionId")
                .param("submissionId", submissionId).update()).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbcClient.sql("DELETE FROM provider_verification_evidence_reference WHERE submission_id = :submissionId")
                .param("submissionId", submissionId).update()).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbcClient.sql("""
                INSERT INTO provider_verification_evidence_reference (submission_id, sort_order, evidence_reference)
                VALUES (:submissionId, 3, 'extra-ref')
                """).param("submissionId", submissionId).update()).isInstanceOf(DataAccessException.class);
    }

    @Test
    void allowsOnlyUnverifiedAndRejectedSourceStates() throws Exception {
        submitAs(ADMIN_ID, PROVIDER_ID, "verification-unverified", "[\"unverified-ref\"]")
                .andExpect(status().isOk());

        resetVerificationState("REJECTED");
        submitAs(ADMIN_ID, PROVIDER_ID, "verification-rejected", "[\"rejected-ref\"]")
                .andExpect(status().isOk());

        for (var sourceState : Set.of("PENDING", "VERIFIED", "SUSPENDED")) {
            resetVerificationState(sourceState);
            submitAs(ADMIN_ID, PROVIDER_ID, "verification-" + sourceState, "[\"state-ref-" + sourceState + "\"]")
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("INVALID_PROVIDER_STATE"));
            assertThat(count("SELECT count(*) FROM provider_verification_submission")).isEqualTo(2);
        }
    }

    @Test
    void preservesPrivacyAndRequiresAnActiveAdministrator() throws Exception {
        submitAs(STAFF_ID, PROVIDER_ID, "verification-staff", "[\"staff-ref\"]")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN_ROLE"));
        submitAs(OTHER_ADMIN_ID, PROVIDER_ID, "verification-cross-provider", "[\"other-ref\"]")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PRIVATE_RESOURCE_NOT_FOUND"));
        submitAs(OUTSIDER_ID, PROVIDER_ID, "verification-outsider", "[\"outsider-ref\"]")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PRIVATE_RESOURCE_NOT_FOUND"));
        jdbcClient.sql("UPDATE provider_staff_membership SET status = 'REMOVED', removed_at = statement_timestamp() WHERE provider_id = :providerId AND account_id = :accountId")
                .param("providerId", PROVIDER_ID).param("accountId", ADMIN_ID).update();
        submitAs(ADMIN_ID, PROVIDER_ID, "verification-inactive", "[\"inactive-ref\"]")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PRIVATE_RESOURCE_NOT_FOUND"));
        assertThat(providerVersion()).isOne();
        assertThat(count("SELECT count(*) FROM provider_verification_submission")).isZero();
    }

    @Test
    void rejectsInvalidEvidenceWithoutPartialRowsOrVersionChanges() throws Exception {
        submitAs(ADMIN_ID, PROVIDER_ID, "verification-empty", "[]")
                .andExpect(status().isUnprocessableEntity());
        submitAs(ADMIN_ID, PROVIDER_ID, "verification-duplicate", "[\"same-ref\",\"same-ref\"]")
                .andExpect(status().isUnprocessableEntity());
        submitAs(ADMIN_ID, PROVIDER_ID, "verification-non-ascii", "[\"ref-\\u00e9\"]")
                .andExpect(status().isUnprocessableEntity());
        submitAs(ADMIN_ID, PROVIDER_ID, "verification-too-long", "[\"%s\"]".formatted("a".repeat(257)))
                .andExpect(status().isUnprocessableEntity());
        assertThat(providerVersion()).isOne();
        assertThat(eligibilityVersion()).isOne();
        assertThat(count("SELECT count(*) FROM provider_verification_submission")).isZero();
        assertThat(count("SELECT count(*) FROM provider_verification_evidence_reference")).isZero();
    }

    @Test
    void replaysExactlyWithoutMoreTransitionsAndRejectsChangedEvidence() throws Exception {
        var first = submitAs(ADMIN_ID, PROVIDER_ID, "verification-replay", "[\"replay-ref\"]")
                .andExpect(status().isOk()).andReturn().getResponse();
        var replay = submitAs(ADMIN_ID, PROVIDER_ID, "verification-replay", "[\"replay-ref\"]")
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", first.getHeader("ETag")))
                .andReturn().getResponse();
        assertThat(replay.getContentAsString()).isEqualTo(first.getContentAsString());

        submitAs(ADMIN_ID, PROVIDER_ID, "verification-replay", "[\"changed-ref\"]")
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(org.springframework.http.MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
        assertThat(providerVersion()).isEqualTo(2);
        assertThat(eligibilityVersion()).isEqualTo(2);
        assertThat(count("SELECT count(*) FROM provider_verification_submission")).isOne();
        assertThat(count("SELECT count(*) FROM audit_event WHERE action = 'provider.verification.submitted'")).isOne();
    }

    @Test
    void rollsBackSubmissionEvidenceTransitionAuditAndIdempotencyWhenAuditFails() throws Exception {
        jdbcClient.sql("""
                CREATE FUNCTION test_fail_provider_verification_audit()
                RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN RAISE EXCEPTION 'forced provider verification audit failure'; END;
                $$
                """).update();
        jdbcClient.sql("""
                CREATE TRIGGER fail_provider_verification_audit BEFORE INSERT ON audit_event
                FOR EACH ROW WHEN (NEW.action = 'provider.verification.submitted')
                EXECUTE FUNCTION test_fail_provider_verification_audit()
                """).update();

        submitAs(ADMIN_ID, PROVIDER_ID, "verification-rollback", "[\"rollback-ref\"]")
                .andExpect(status().isInternalServerError());

        assertThat(providerStatus()).isEqualTo("UNVERIFIED");
        assertThat(providerVersion()).isOne();
        assertThat(eligibilityVersion()).isOne();
        assertThat(count("SELECT count(*) FROM provider_verification_submission")).isZero();
        assertThat(count("SELECT count(*) FROM provider_verification_evidence_reference")).isZero();
        assertThat(count("SELECT count(*) FROM audit_event")).isZero();
        assertThat(count("SELECT count(*) FROM idempotency_record")).isZero();
    }

    @Test
    void publishesTheVerificationSubmissionRouteInExecutableOpenApi() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/v1/providers/{providerId}/verification-submissions'].post.operationId").value("submitProviderVerification"))
                .andExpect(jsonPath("$.paths['/api/v1/providers/{providerId}/verification-submissions'].post.responses['200'].headers.ETag").exists())
                .andExpect(jsonPath("$.paths['/api/v1/providers/{providerId}/verification-submissions'].post.responses['409']").exists());
    }

    private org.springframework.test.web.servlet.ResultActions submitAs(
            UUID actorId, UUID providerId, String idempotencyKey, String body) throws Exception {
        return mockMvc.perform(post("/api/v1/providers/{providerId}/verification-submissions", providerId)
                .with(authentication(authenticationFor(actorId)))
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content("{\"evidenceReferences\":%s}".formatted(body))
                .header("Idempotency-Key", idempotencyKey)
                .header(CorrelationIdFilter.HEADER_NAME, CORRELATION_ID));
    }

    private TestingAuthenticationToken authenticationFor(UUID accountId) {
        return new TestingAuthenticationToken(new AuthenticatedActor(accountId, Set.of()), null, "ROLE_USER");
    }

    private void resetVerificationState(String status) {
        jdbcClient.sql("""
                UPDATE provider_organization
                SET verification_status = :status, version = 1, eligibility_version = 1
                WHERE provider_id = :providerId
                """).param("status", status).param("providerId", PROVIDER_ID).update();
    }

    private void insertAccount(UUID accountId, String displayName) {
        jdbcClient.sql("INSERT INTO identity_account (account_id, display_name) VALUES (:accountId, :displayName)")
                .param("accountId", accountId).param("displayName", displayName).update();
    }

    private void insertProvider(UUID providerId, String verificationStatus) {
        jdbcClient.sql("""
                INSERT INTO provider_organization (provider_id, display_name, verification_status)
                VALUES (:providerId, 'Provider venues', :verificationStatus)
                """).param("providerId", providerId).param("verificationStatus", verificationStatus).update();
    }

    private void insertMembership(UUID providerId, UUID accountId, String role, String status) {
        jdbcClient.sql("""
                INSERT INTO provider_staff_membership (provider_id, account_id, role, status)
                VALUES (:providerId, :accountId, :role, :status)
                """).param("providerId", providerId).param("accountId", accountId).param("role", role).param("status", status).update();
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

    private java.util.List<String> evidenceReferences() {
        return jdbcClient.sql("""
                SELECT evidence_reference FROM provider_verification_evidence_reference
                ORDER BY sort_order
                """).query(String.class).list();
    }

    private String auditMetadata() {
        return jdbcClient.sql("SELECT metadata::TEXT FROM audit_event WHERE action = 'provider.verification.submitted'")
                .query(String.class).single();
    }

    private String idempotencyReplayState() {
        return jdbcClient.sql("SELECT replay_state::TEXT FROM idempotency_record").query(String.class).single();
    }

    private long count(String sql) {
        return jdbcClient.sql(sql).query(Long.class).single();
    }
}

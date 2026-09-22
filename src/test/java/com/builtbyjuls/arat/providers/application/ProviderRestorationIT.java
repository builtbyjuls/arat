package com.builtbyjuls.arat.providers.application;

import static org.assertj.core.api.Assertions.assertThat;
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
import com.builtbyjuls.arat.providers.api.ProviderRestorationException;
import com.builtbyjuls.arat.providers.api.RestoreProviderCommand;
import com.builtbyjuls.arat.testing.ConcurrentDatabaseWorkers;
import com.builtbyjuls.arat.web.CorrelationIdFilter;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

@SpringBootTest
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ProviderRestorationIT extends PostgreSqlIntegrationTest {

    private static final UUID PROVIDER_ID = UUID.fromString("70000000-0000-4000-8000-000000000051");
    private static final UUID ADMIN_ID = UUID.fromString("80000000-0000-4000-8000-000000000051");
    private static final UUID OPERATOR_ID = UUID.fromString("80000000-0000-4000-8000-000000000052");
    private static final UUID NON_OPERATOR_ID = UUID.fromString("80000000-0000-4000-8000-000000000053");
    private static final String CORRELATION_ID = "provider-restoration-test-123";

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private CorrelationIdFilter correlationIdFilter;

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private ProviderRestorationService restorationService;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private DataSource dataSource;

    private MockMvc mockMvc;

    @BeforeEach
    void prepareDatabase() {
        removeAuditFailureTrigger();
        truncateProviderTables();
        jdbcClient.sql("DELETE FROM audit_event").update();
        jdbcClient.sql("DELETE FROM idempotency_record").update();
        jdbcClient.sql("DELETE FROM identity_account WHERE account_id IN (:adminId, :operatorId, :nonOperatorId)")
                .param("adminId", ADMIN_ID)
                .param("operatorId", OPERATOR_ID)
                .param("nonOperatorId", NON_OPERATOR_ID)
                .update();
        insertAccount(ADMIN_ID, "Provider administrator");
        insertAccount(OPERATOR_ID, "Platform operator");
        insertAccount(NON_OPERATOR_ID, "Provider outsider");
        seedProvider("UNVERIFIED", 1, 1);
        insertActiveAdmin();
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .addFilters(correlationIdFilter)
                .apply(springSecurity())
                .build();
    }

    @AfterEach
    void cleanDatabase() {
        removeAuditFailureTrigger();
        truncateProviderTables();
    }

    @Test
    void fullLifecycleRestoresOnlySuspendedProviderWithANewEligibilityVersion() throws Exception {
        submitVerification()
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"2\""))
                .andExpect(jsonPath("$.providerVersion").value(2));
        var submissionId = jdbcClient.sql("SELECT submission_id FROM provider_verification_submission")
                .query(UUID.class)
                .single();

        decideVerification(submissionId)
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"3\""))
                .andExpect(jsonPath("$.verificationStatus").value("VERIFIED"));
        suspend()
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"4\""))
                .andExpect(jsonPath("$.verificationStatus").value("SUSPENDED"));

        var first = restoreAsOperator("restore-lifecycle")
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"5\""))
                .andExpect(jsonPath("$.providerId").value(PROVIDER_ID.toString()))
                .andExpect(jsonPath("$.verificationStatus").value("VERIFIED"))
                .andExpect(jsonPath("$.providerVersion").value(5))
                .andExpect(jsonPath("$.eligibilityVersion").value(5))
                .andReturn()
                .getResponse();
        var replay = restoreAsOperator("restore-lifecycle")
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", first.getHeader("ETag")))
                .andReturn()
                .getResponse();
        assertThat(replay.getContentAsString()).isEqualTo(first.getContentAsString());

        restoreAsOperator("restore-again")
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(org.springframework.http.MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("INVALID_PROVIDER_STATE"));

        assertThat(providerStatus()).isEqualTo("VERIFIED");
        assertThat(providerVersion()).isEqualTo(5);
        assertThat(eligibilityVersion()).isEqualTo(5);
        assertThat(count("SELECT count(*) FROM audit_event WHERE action = 'provider.verification.submitted'")).isOne();
        assertThat(count("SELECT count(*) FROM audit_event WHERE action = 'provider.verification.accepted'")).isOne();
        assertThat(count("SELECT count(*) FROM audit_event WHERE action = 'provider.suspended'")).isOne();
        assertThat(count("SELECT count(*) FROM audit_event WHERE action = 'provider.restored'")).isOne();
        assertThat(auditFacts()).contains("provider.restored", OPERATOR_ID.toString(), PROVIDER_ID.toString())
                .doesNotContain("lifecycle-evidence-reference");
        assertThat(count("SELECT count(*) FROM idempotency_record WHERE operation = 'operations.providers.restoration.create'")).isOne();
    }

    @Test
    void requiresOperatorAndAllowsRestorationOnlyFromSuspended() throws Exception {
        restoreAs(NON_OPERATOR_ID, Set.of(), UUID.randomUUID(), "non-operator")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN_PLATFORM_ROLE"));
        restoreAs(OPERATOR_ID, Set.of(PlatformRole.PLATFORM_OPERATOR), UUID.randomUUID(), "missing-provider")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PRIVATE_RESOURCE_NOT_FOUND"));
        restoreAsOperator("")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        restoreAsOperator("unverified")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_PROVIDER_STATE"));
        submitVerification().andExpect(status().isOk());
        restoreAsOperator("pending")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_PROVIDER_STATE"));
        var submissionId = jdbcClient.sql("SELECT submission_id FROM provider_verification_submission")
                .query(UUID.class)
                .single();
        decideVerification(submissionId).andExpect(status().isOk());
        restoreAsOperator("verified")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_PROVIDER_STATE"));
        setProviderState("REJECTED", 3, 3);
        restoreAsOperator("rejected")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_PROVIDER_STATE"));
        assertThat(providerVersion()).isEqualTo(3);
        assertThat(eligibilityVersion()).isEqualTo(3);
        assertThat(count("SELECT count(*) FROM audit_event WHERE action = 'provider.restored'")).isZero();
        assertThat(count("SELECT count(*) FROM idempotency_record WHERE operation = 'operations.providers.restoration.create'")).isZero();
    }

    @Test
    void rollsBackRestorationAndItsIdempotencyClaimWhenAuditAppendFails() throws Exception {
        setProviderState("SUSPENDED", 4, 4);
        jdbcClient.sql("""
                CREATE FUNCTION test_fail_provider_restoration_audit()
                RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN RAISE EXCEPTION 'forced provider restoration audit failure'; END;
                $$
                """).update();
        jdbcClient.sql("""
                CREATE TRIGGER fail_provider_restoration_audit BEFORE INSERT ON audit_event
                FOR EACH ROW WHEN (NEW.action = 'provider.restored')
                EXECUTE FUNCTION test_fail_provider_restoration_audit()
                """).update();

        restoreAsOperator("restore-rollback").andExpect(status().isInternalServerError());

        assertThat(providerStatus()).isEqualTo("SUSPENDED");
        assertThat(providerVersion()).isEqualTo(4);
        assertThat(eligibilityVersion()).isEqualTo(4);
        assertThat(count("SELECT count(*) FROM audit_event WHERE action = 'provider.restored'")).isZero();
        assertThat(count("SELECT count(*) FROM idempotency_record WHERE operation = 'operations.providers.restoration.create'")).isZero();
    }

    @Test
    void concurrentRestorationsHaveOneWinnerAndOneVersionAdvance() throws Exception {
        setProviderState("SUSPENDED", 4, 4);

        var outcomes = ConcurrentDatabaseWorkers.runOrdered(
                dataSource,
                transactionManager,
                "SELECT provider_id FROM provider_organization WHERE provider_id = ? FOR NO KEY UPDATE",
                PROVIDER_ID,
                () -> restorationService.restore(restorationCommand("restore-race-first")),
                () -> restorationService.restore(restorationCommand("restore-race-second")));

        assertThat(outcomes.getFirst().succeeded()).isTrue();
        assertThat(outcomes.get(1).failure())
                .isInstanceOfSatisfying(ProviderRestorationException.class,
                        exception -> assertThat(exception.reason())
                                .isEqualTo(ProviderRestorationException.Reason.INVALID_PROVIDER_STATE));
        assertThat(providerStatus()).isEqualTo("VERIFIED");
        assertThat(providerVersion()).isEqualTo(5);
        assertThat(eligibilityVersion()).isEqualTo(5);
        assertThat(count("SELECT count(*) FROM audit_event WHERE action = 'provider.restored'")).isOne();
        assertThat(count("SELECT count(*) FROM idempotency_record WHERE operation = 'operations.providers.restoration.create'")).isOne();
    }

    @Test
    void publishesTheRestorationRouteInExecutableOpenApi() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/v1/operations/providers/{providerId}/restoration'].post.operationId")
                        .value("restoreProvider"))
                .andExpect(jsonPath("$.paths['/api/v1/operations/providers/{providerId}/restoration'].post.parameters[?(@.name == 'Idempotency-Key')].required")
                        .value(true))
                .andExpect(jsonPath("$.paths['/api/v1/operations/providers/{providerId}/restoration'].post.responses['200'].headers.ETag")
                        .exists())
                .andExpect(jsonPath("$.paths['/api/v1/operations/providers/{providerId}/restoration'].post.responses['403']")
                        .exists())
                .andExpect(jsonPath("$.paths['/api/v1/operations/providers/{providerId}/restoration'].post.responses['422']")
                        .exists());
    }

    private org.springframework.test.web.servlet.ResultActions submitVerification() throws Exception {
        return mockMvc.perform(post("/api/v1/providers/{providerId}/verification-submissions", PROVIDER_ID)
                .with(authentication(authenticationFor(ADMIN_ID, Set.of())))
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content("{\"evidenceReferences\":[\"lifecycle-evidence-reference\"]}")
                .header("Idempotency-Key", "submit-lifecycle")
                .header(CorrelationIdFilter.HEADER_NAME, CORRELATION_ID));
    }

    private org.springframework.test.web.servlet.ResultActions decideVerification(UUID submissionId) throws Exception {
        return mockMvc.perform(post("/api/v1/operations/providers/{providerId}/verification-decisions", PROVIDER_ID)
                .with(authentication(authenticationFor(OPERATOR_ID, Set.of(PlatformRole.PLATFORM_OPERATOR))))
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content("{\"submissionId\":\"%s\",\"decision\":\"ACCEPT\"}".formatted(submissionId))
                .header("Idempotency-Key", "accept-lifecycle")
                .header(CorrelationIdFilter.HEADER_NAME, CORRELATION_ID));
    }

    private org.springframework.test.web.servlet.ResultActions suspend() throws Exception {
        return mockMvc.perform(post("/api/v1/operations/providers/{providerId}/suspension", PROVIDER_ID)
                .with(authentication(authenticationFor(OPERATOR_ID, Set.of(PlatformRole.PLATFORM_OPERATOR))))
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"Lifecycle suspension.\"}")
                .header("Idempotency-Key", "suspend-lifecycle")
                .header(CorrelationIdFilter.HEADER_NAME, CORRELATION_ID));
    }

    private org.springframework.test.web.servlet.ResultActions restoreAsOperator(String idempotencyKey) throws Exception {
        return restoreAs(OPERATOR_ID, Set.of(PlatformRole.PLATFORM_OPERATOR), PROVIDER_ID, idempotencyKey);
    }

    private RestoreProviderCommand restorationCommand(String idempotencyKey) {
        return new RestoreProviderCommand(
                OPERATOR_ID,
                Set.of(PlatformRole.PLATFORM_OPERATOR),
                PROVIDER_ID,
                idempotencyKey,
                CORRELATION_ID);
    }

    private org.springframework.test.web.servlet.ResultActions restoreAs(
            UUID actorId, Set<PlatformRole> platformRoles, UUID providerId, String idempotencyKey) throws Exception {
        return mockMvc.perform(post("/api/v1/operations/providers/{providerId}/restoration", providerId)
                .with(authentication(authenticationFor(actorId, platformRoles)))
                .header("Idempotency-Key", idempotencyKey)
                .header(CorrelationIdFilter.HEADER_NAME, CORRELATION_ID));
    }

    private TestingAuthenticationToken authenticationFor(UUID accountId, Set<PlatformRole> platformRoles) {
        return new TestingAuthenticationToken(new AuthenticatedActor(accountId, platformRoles), null, "ROLE_USER");
    }

    private void seedProvider(String verificationStatus, long version, long eligibilityVersion) {
        jdbcClient.sql("""
                INSERT INTO provider_organization (
                    provider_id, display_name, verification_status, version, eligibility_version
                ) VALUES (:providerId, 'Provider venues', :verificationStatus, :version, :eligibilityVersion)
                """)
                .param("providerId", PROVIDER_ID)
                .param("verificationStatus", verificationStatus)
                .param("version", version)
                .param("eligibilityVersion", eligibilityVersion)
                .update();
    }

    private void setProviderState(String verificationStatus, long version, long eligibilityVersion) {
        jdbcClient.sql("""
                UPDATE provider_organization
                SET verification_status = :verificationStatus,
                    current_verification_submission_id = NULL,
                    version = :version,
                    eligibility_version = :eligibilityVersion
                WHERE provider_id = :providerId
                """)
                .param("providerId", PROVIDER_ID)
                .param("verificationStatus", verificationStatus)
                .param("version", version)
                .param("eligibilityVersion", eligibilityVersion)
                .update();
    }

    private void insertActiveAdmin() {
        jdbcClient.sql("""
                INSERT INTO provider_staff_membership (provider_id, account_id, role, status)
                VALUES (:providerId, :accountId, 'ADMIN', 'ACTIVE')
                """)
                .param("providerId", PROVIDER_ID)
                .param("accountId", ADMIN_ID)
                .update();
    }

    private void insertAccount(UUID accountId, String displayName) {
        jdbcClient.sql("INSERT INTO identity_account (account_id, display_name) VALUES (:accountId, :displayName)")
                .param("accountId", accountId)
                .param("displayName", displayName)
                .update();
    }

    private void truncateProviderTables() {
        jdbcClient.sql("""
                TRUNCATE TABLE provider_suspension,
                    provider_verification_decision,
                    provider_verification_evidence_reference,
                    provider_verification_submission,
                    provider_service_area,
                    provider_supported_category,
                    provider_staff_membership,
                    provider_organization
                """).update();
    }

    private void removeAuditFailureTrigger() {
        jdbcClient.sql("DROP TRIGGER IF EXISTS fail_provider_restoration_audit ON audit_event").update();
        jdbcClient.sql("DROP FUNCTION IF EXISTS test_fail_provider_restoration_audit()").update();
    }

    private String providerStatus() {
        return jdbcClient.sql("SELECT verification_status FROM provider_organization WHERE provider_id = :providerId")
                .param("providerId", PROVIDER_ID)
                .query(String.class)
                .single();
    }

    private long providerVersion() {
        return jdbcClient.sql("SELECT version FROM provider_organization WHERE provider_id = :providerId")
                .param("providerId", PROVIDER_ID)
                .query(Long.class)
                .single();
    }

    private long eligibilityVersion() {
        return jdbcClient.sql("SELECT eligibility_version FROM provider_organization WHERE provider_id = :providerId")
                .param("providerId", PROVIDER_ID)
                .query(Long.class)
                .single();
    }

    private String auditFacts() {
        return jdbcClient.sql("""
                SELECT action || ':' || actor_id || ':' || subject_id || ':' || metadata::TEXT
                FROM audit_event
                WHERE action = 'provider.restored'
                """).query(String.class).single();
    }

    private long count(String sql) {
        return jdbcClient.sql(sql).query(Long.class).single();
    }
}

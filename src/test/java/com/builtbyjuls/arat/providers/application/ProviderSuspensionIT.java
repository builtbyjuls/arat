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
import com.builtbyjuls.arat.providers.api.ProviderSuspensionException;
import com.builtbyjuls.arat.providers.api.SuspendProviderCommand;
import com.builtbyjuls.arat.testing.ConcurrentDatabaseWorkers;
import com.builtbyjuls.arat.web.CorrelationIdFilter;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
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
class ProviderSuspensionIT extends PostgreSqlIntegrationTest {

    private static final UUID PROVIDER_ID = UUID.fromString("70000000-0000-4000-8000-000000000041");
    private static final UUID OPERATOR_ID = UUID.fromString("80000000-0000-4000-8000-000000000041");
    private static final UUID NON_OPERATOR_ID = UUID.fromString("80000000-0000-4000-8000-000000000042");
    private static final String CORRELATION_ID = "provider-suspension-test-123";

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
    private ProviderSuspensionService suspensionService;

    private MockMvc mockMvc;

    @BeforeEach
    void prepareDatabase() {
        removeAuditFailureTrigger();
        dropRecipientFixture();
        truncateProviderTables();
        jdbcClient.sql("DELETE FROM audit_event").update();
        jdbcClient.sql("DELETE FROM idempotency_record").update();
        jdbcClient.sql("DELETE FROM identity_account WHERE account_id IN (:operatorId, :nonOperatorId)")
                .param("operatorId", OPERATOR_ID)
                .param("nonOperatorId", NON_OPERATOR_ID)
                .update();
        insertAccount(OPERATOR_ID, "Platform operator");
        insertAccount(NON_OPERATOR_ID, "Provider administrator");
        seedProvider("VERIFIED");
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .addFilters(correlationIdFilter)
                .apply(springSecurity())
                .build();
    }

    @AfterEach
    void cleanDatabase() {
        removeAuditFailureTrigger();
        dropRecipientFixture();
        truncateProviderTables();
    }

    @Test
    void operatorSuspendsVerifiedProviderAndPersistsOneSanitizedAuditTrail() throws Exception {
        var response = suspendAsOperator("suspend-provider", "  Repeated identity mismatch.  ")
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"4\""))
                .andExpect(jsonPath("$.providerId").value(PROVIDER_ID.toString()))
                .andExpect(jsonPath("$.reason").value("Repeated identity mismatch."))
                .andExpect(jsonPath("$.verificationStatus").value("SUSPENDED"))
                .andExpect(jsonPath("$.providerVersion").value(4))
                .andExpect(jsonPath("$.eligibilityVersion").value(4))
                .andReturn().getResponse();

        var suspensionId = UUID.fromString(readJsonField(response.getContentAsString(), "suspensionId"));
        assertThat(providerStatus()).isEqualTo("SUSPENDED");
        assertThat(providerVersion()).isEqualTo(4);
        assertThat(eligibilityVersion()).isEqualTo(4);
        assertThat(suspensionReason()).isEqualTo("Repeated identity mismatch.");
        assertThat(count("SELECT count(*) FROM provider_suspension")).isOne();
        assertThat(count("SELECT count(*) FROM audit_event WHERE action = 'provider.suspended'")).isOne();
        assertThat(auditMetadata()).contains(PROVIDER_ID.toString(), suspensionId.toString())
                .doesNotContain("Repeated identity mismatch");

        assertThatThrownBy(() -> jdbcClient.sql("""
                        UPDATE provider_suspension
                        SET suspension_reason = 'Changed reason'
                        WHERE suspension_id = :suspensionId
                        """).param("suspensionId", suspensionId).update())
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbcClient.sql("DELETE FROM provider_suspension WHERE suspension_id = :suspensionId")
                        .param("suspensionId", suspensionId).update())
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void requiresPlatformOperatorBeforeTargetLookupAndValidatesTheReason() throws Exception {
        suspendAs(NON_OPERATOR_ID, Set.of(), PROVIDER_ID, "non-operator", "Policy review.")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN_PLATFORM_ROLE"));
        suspendAs(NON_OPERATOR_ID, Set.of(), UUID.randomUUID(), "hidden-target", "Policy review.")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN_PLATFORM_ROLE"));
        suspendAs(OPERATOR_ID, Set.of(PlatformRole.PLATFORM_OPERATOR), UUID.randomUUID(),
                        "missing-provider", "Policy review.")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PRIVATE_RESOURCE_NOT_FOUND"));

        mockMvc.perform(post("/api/v1/operations/providers/{providerId}/suspension", PROVIDER_ID)
                        .with(authentication(authenticationFor(OPERATOR_ID, Set.of(PlatformRole.PLATFORM_OPERATOR))))
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"Policy review.\"}"))
                .andExpect(status().isBadRequest());
        suspendAsOperator("blank-reason", "   ").andExpect(status().isUnprocessableEntity());
        suspendAsOperator("unicode-blank-reason", "\u2003").andExpect(status().isUnprocessableEntity());
        suspendAsOperator("long-reason", "a".repeat(501)).andExpect(status().isUnprocessableEntity());
        mockMvc.perform(post("/api/v1/operations/providers/{providerId}/suspension", PROVIDER_ID)
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"Policy review.\"}")
                        .header("Idempotency-Key", "unauthenticated"))
                .andExpect(status().isUnauthorized());

        assertThat(providerStatus()).isEqualTo("VERIFIED");
        assertThat(count("SELECT count(*) FROM provider_suspension")).isZero();
        assertThat(count("SELECT count(*) FROM audit_event")).isZero();
        assertThat(count("SELECT count(*) FROM idempotency_record")).isZero();
    }

    @Test
    void allowsSuspensionOnlyFromVerified() throws Exception {
        for (var state : List.of("UNVERIFIED", "PENDING", "REJECTED", "SUSPENDED")) {
            resetProviderState(state);
            suspendAsOperator("wrong-state-" + state, "State does not permit suspension.")
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("INVALID_PROVIDER_STATE"));
            assertThat(providerVersion()).isEqualTo(3);
            assertThat(eligibilityVersion()).isEqualTo(3);
        }
        assertThat(count("SELECT count(*) FROM provider_suspension")).isZero();
        assertThat(count("SELECT count(*) FROM audit_event")).isZero();
        assertThat(count("SELECT count(*) FROM idempotency_record")).isZero();
    }

    @Test
    void exactReplayReturnsOriginalSuspensionWithoutAnotherVersionAdvance() throws Exception {
        var first = suspendAsOperator("suspension-replay", "Trust review required.")
                .andExpect(status().isOk()).andReturn().getResponse();
        var replay = suspendAsOperator("suspension-replay", "Trust review required.")
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", first.getHeader("ETag")))
                .andReturn().getResponse();
        assertThat(replay.getContentAsString()).isEqualTo(first.getContentAsString());

        suspendAsOperator("suspension-replay", "Different reason.")
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(org.springframework.http.MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
        assertThat(providerVersion()).isEqualTo(4);
        assertThat(eligibilityVersion()).isEqualTo(4);
        assertThat(count("SELECT count(*) FROM provider_suspension")).isOne();
        assertThat(count("SELECT count(*) FROM audit_event")).isOne();
        assertThat(count("SELECT count(*) FROM idempotency_record")).isOne();
    }

    @Test
    void concurrentSuspensionsHaveOneWinnerAndOneVersionAdvance() throws Exception {
        var outcomes = ConcurrentDatabaseWorkers.runOrdered(
                dataSource,
                transactionManager,
                "SELECT provider_id FROM provider_organization WHERE provider_id = ? FOR NO KEY UPDATE",
                PROVIDER_ID,
                () -> suspensionService.suspend(suspensionCommand("race-first", "First policy reason.")),
                () -> suspensionService.suspend(suspensionCommand("race-second", "Second policy reason.")));

        assertThat(outcomes.getFirst().succeeded()).isTrue();
        assertThat(outcomes.get(1).failure())
                .isInstanceOfSatisfying(ProviderSuspensionException.class,
                        exception -> assertThat(exception.reason())
                                .isEqualTo(ProviderSuspensionException.Reason.INVALID_PROVIDER_STATE));
        assertThat(providerStatus()).isEqualTo("SUSPENDED");
        assertThat(providerVersion()).isEqualTo(4);
        assertThat(eligibilityVersion()).isEqualTo(4);
        assertThat(count("SELECT count(*) FROM provider_suspension")).isOne();
        assertThat(count("SELECT count(*) FROM audit_event")).isOne();
        assertThat(count("SELECT count(*) FROM idempotency_record")).isOne();
    }

    @Test
    void rollsBackTransitionSuspensionAuditAndIdempotencyWhenAuditFails() throws Exception {
        jdbcClient.sql("""
                CREATE FUNCTION test_fail_provider_suspension_audit()
                RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN RAISE EXCEPTION 'forced provider suspension audit failure'; END;
                $$
                """).update();
        jdbcClient.sql("""
                CREATE TRIGGER fail_provider_suspension_audit BEFORE INSERT ON audit_event
                FOR EACH ROW WHEN (NEW.action = 'provider.suspended')
                EXECUTE FUNCTION test_fail_provider_suspension_audit()
                """).update();

        suspendAsOperator("suspension-rollback", "Audit rollback proof.")
                .andExpect(status().isInternalServerError());

        assertThat(providerStatus()).isEqualTo("VERIFIED");
        assertThat(providerVersion()).isEqualTo(3);
        assertThat(eligibilityVersion()).isEqualTo(3);
        assertThat(count("SELECT count(*) FROM provider_suspension")).isZero();
        assertThat(count("SELECT count(*) FROM audit_event")).isZero();
        assertThat(count("SELECT count(*) FROM idempotency_record")).isZero();
    }

    @Test
    void providerRootAndRecipientForeignKeyLocksRemainCompatible() throws Exception {
        jdbcClient.sql("""
                CREATE TABLE provider_recipient_lock_fixture (
                    recipient_id UUID PRIMARY KEY,
                    provider_id UUID NOT NULL REFERENCES provider_organization (provider_id)
                )
                """).update();

        try (var providerLockOwner = dataSource.getConnection()) {
            providerLockOwner.setAutoCommit(false);
            configureConnection(providerLockOwner, "arat-lock-provider-owner");
            selectProviderForNoKeyUpdate(providerLockOwner);

            runCompatibleWorker("recipient FK insertion", () -> {
                try (var recipientConnection = dataSource.getConnection()) {
                    recipientConnection.setAutoCommit(false);
                    configureConnection(recipientConnection, "arat-lock-recipient-insert");
                    insertRecipient(recipientConnection, UUID.fromString("72000000-0000-4000-8000-000000000041"));
                    recipientConnection.commit();
                    return null;
                }
            });
            providerLockOwner.rollback();
        }
        assertThat(count("SELECT count(*) FROM provider_recipient_lock_fixture")).isOne();

        try (var recipientLockOwner = dataSource.getConnection()) {
            recipientLockOwner.setAutoCommit(false);
            configureConnection(recipientLockOwner, "arat-lock-recipient-owner");
            insertRecipient(recipientLockOwner, UUID.fromString("72000000-0000-4000-8000-000000000042"));

            runCompatibleWorker("provider non-key update", () -> {
                try (var providerConnection = dataSource.getConnection()) {
                    providerConnection.setAutoCommit(false);
                    configureConnection(providerConnection, "arat-lock-provider-update");
                    try (var statement = providerConnection.prepareStatement("""
                            UPDATE provider_organization
                            SET display_name = 'Lock-compatible provider'
                            WHERE provider_id = ?
                            """)) {
                        statement.setObject(1, PROVIDER_ID);
                        assertThat(statement.executeUpdate()).isOne();
                    }
                    providerConnection.commit();
                    return null;
                }
            });
            recipientLockOwner.rollback();
        }
        assertThat(jdbcClient.sql("SELECT display_name FROM provider_organization WHERE provider_id = :providerId")
                .param("providerId", PROVIDER_ID).query(String.class).single())
                .isEqualTo("Lock-compatible provider");
    }

    @Test
    void publishesTheSuspensionRouteInExecutableOpenApi() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/v1/operations/providers/{providerId}/suspension'].post.operationId")
                        .value("suspendProvider"))
                .andExpect(jsonPath("$.paths['/api/v1/operations/providers/{providerId}/suspension'].post.parameters[?(@.name == 'Idempotency-Key')].required")
                        .value(true))
                .andExpect(jsonPath("$.paths['/api/v1/operations/providers/{providerId}/suspension'].post.responses['200'].headers.ETag")
                        .exists())
                .andExpect(jsonPath("$.paths['/api/v1/operations/providers/{providerId}/suspension'].post.responses['403']")
                        .exists());
    }

    private org.springframework.test.web.servlet.ResultActions suspendAsOperator(String key, String reason) throws Exception {
        return suspendAs(OPERATOR_ID, Set.of(PlatformRole.PLATFORM_OPERATOR), PROVIDER_ID, key, reason);
    }

    private org.springframework.test.web.servlet.ResultActions suspendAs(
            UUID actorId,
            Set<PlatformRole> roles,
            UUID providerId,
            String key,
            String reason) throws Exception {
        return mockMvc.perform(post("/api/v1/operations/providers/{providerId}/suspension", providerId)
                .with(authentication(authenticationFor(actorId, roles)))
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"%s\"}".formatted(reason))
                .header("Idempotency-Key", key)
                .header(CorrelationIdFilter.HEADER_NAME, CORRELATION_ID));
    }

    private SuspendProviderCommand suspensionCommand(String key, String reason) {
        return new SuspendProviderCommand(
                OPERATOR_ID,
                Set.of(PlatformRole.PLATFORM_OPERATOR),
                PROVIDER_ID,
                key,
                reason,
                CORRELATION_ID);
    }

    private TestingAuthenticationToken authenticationFor(UUID accountId, Set<PlatformRole> roles) {
        return new TestingAuthenticationToken(new AuthenticatedActor(accountId, roles), null, "ROLE_USER");
    }

    private void seedProvider(String verificationStatus) {
        jdbcClient.sql("""
                INSERT INTO provider_organization (
                    provider_id, display_name, verification_status, version, eligibility_version
                ) VALUES (:providerId, 'Provider venues', :verificationStatus, 3, 3)
                """)
                .param("providerId", PROVIDER_ID)
                .param("verificationStatus", verificationStatus)
                .update();
    }

    private void resetProviderState(String state) {
        jdbcClient.sql("""
                UPDATE provider_organization
                SET verification_status = 'UNVERIFIED',
                    current_verification_submission_id = NULL,
                    version = 3,
                    eligibility_version = 3
                WHERE provider_id = :providerId
                """).param("providerId", PROVIDER_ID).update();
        if (state.equals("PENDING")) {
            var submissionId = UUID.randomUUID();
            new TransactionTemplate(transactionManager).executeWithoutResult(transactionStatus -> {
                jdbcClient.sql("""
                        INSERT INTO provider_verification_submission (
                            submission_id, provider_id, submitted_by_account_id, evidence_count
                        ) VALUES (:submissionId, :providerId, :operatorId, 1)
                        """)
                        .param("submissionId", submissionId)
                        .param("providerId", PROVIDER_ID)
                        .param("operatorId", OPERATOR_ID)
                        .update();
                jdbcClient.sql("""
                        INSERT INTO provider_verification_evidence_reference (
                            submission_id, sort_order, evidence_reference
                        ) VALUES (:submissionId, 1, 'pending-state-reference')
                        """).param("submissionId", submissionId).update();
                jdbcClient.sql("""
                        UPDATE provider_organization
                        SET verification_status = 'PENDING', current_verification_submission_id = :submissionId
                        WHERE provider_id = :providerId
                        """)
                        .param("submissionId", submissionId)
                        .param("providerId", PROVIDER_ID)
                        .update();
            });
        } else {
            jdbcClient.sql("UPDATE provider_organization SET verification_status = :state WHERE provider_id = :providerId")
                    .param("state", state)
                    .param("providerId", PROVIDER_ID)
                    .update();
        }
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
        jdbcClient.sql("DROP TRIGGER IF EXISTS fail_provider_suspension_audit ON audit_event").update();
        jdbcClient.sql("DROP FUNCTION IF EXISTS test_fail_provider_suspension_audit()").update();
    }

    private void dropRecipientFixture() {
        jdbcClient.sql("DROP TABLE IF EXISTS provider_recipient_lock_fixture").update();
    }

    private void configureConnection(Connection connection, String applicationName) throws SQLException {
        try (var statement = connection.createStatement()) {
            statement.execute("SET LOCAL lock_timeout = '3s'");
            statement.execute("SET LOCAL statement_timeout = '3s'");
            statement.execute("SET LOCAL application_name = '" + applicationName + "'");
        }
    }

    private void selectProviderForNoKeyUpdate(Connection connection) throws SQLException {
        try (var statement = connection.prepareStatement("""
                SELECT provider_id
                FROM provider_organization
                WHERE provider_id = ?
                FOR NO KEY UPDATE
                """)) {
            statement.setObject(1, PROVIDER_ID);
            try (var result = statement.executeQuery()) {
                assertThat(result.next()).isTrue();
            }
        }
    }

    private void insertRecipient(Connection connection, UUID recipientId) throws SQLException {
        try (var statement = connection.prepareStatement("""
                INSERT INTO provider_recipient_lock_fixture (recipient_id, provider_id)
                VALUES (?, ?)
                """)) {
            statement.setObject(1, recipientId);
            statement.setObject(2, PROVIDER_ID);
            assertThat(statement.executeUpdate()).isOne();
        }
    }

    private <T> T runCompatibleWorker(String operation, Callable<T> work) throws Exception {
        var executor = Executors.newSingleThreadExecutor(
                Thread.ofPlatform().daemon(true).name("provider-lock-compatibility-worker").factory());
        var result = executor.submit(work);
        try {
            return result.get(Duration.ofSeconds(5).toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException exception) {
            throw new AssertionError(operation + " did not progress while the compatible lock was held; "
                    + lockDiagnostics(), exception);
        } catch (ExecutionException exception) {
            var cause = exception.getCause();
            if (cause instanceof Exception workerException) {
                throw workerException;
            }
            throw exception;
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    private String lockDiagnostics() {
        return jdbcClient.sql("""
                SELECT coalesce(string_agg(
                    application_name || ':' || state || ':'
                    || coalesce(wait_event_type, 'none') || ':' || coalesce(wait_event, 'none'),
                    ', ' ORDER BY application_name), 'no lock test workers')
                FROM pg_stat_activity
                WHERE application_name LIKE 'arat-lock-%'
                """).query(String.class).single();
    }

    private String readJsonField(String json, String field) {
        var marker = "\"" + field + "\":\"";
        var start = json.indexOf(marker) + marker.length();
        return json.substring(start, json.indexOf('"', start));
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

    private String suspensionReason() {
        return jdbcClient.sql("SELECT suspension_reason FROM provider_suspension")
                .query(String.class).single();
    }

    private String auditMetadata() {
        return jdbcClient.sql("SELECT metadata::TEXT FROM audit_event")
                .query(String.class).single();
    }

    private long count(String sql) {
        return jdbcClient.sql(sql).query(Long.class).single();
    }
}

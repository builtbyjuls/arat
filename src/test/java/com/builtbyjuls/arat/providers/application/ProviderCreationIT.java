package com.builtbyjuls.arat.providers.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.builtbyjuls.arat.PostgreSqlIntegrationTest;
import com.builtbyjuls.arat.identity.testing.WithAratActor;
import com.builtbyjuls.arat.providers.api.CreateProviderCommand;
import com.builtbyjuls.arat.providers.api.ProviderRepresentation;
import com.builtbyjuls.arat.providers.domain.ProviderCategory;
import com.builtbyjuls.arat.web.CorrelationIdFilter;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

@SpringBootTest
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ProviderCreationIT extends PostgreSqlIntegrationTest {

    private static final UUID CREATOR_ID = UUID.fromString("10000000-0000-4000-8000-000000000004");
    private static final String CORRELATION_ID = "provider-create-test-123";

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private CorrelationIdFilter correlationIdFilter;

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private com.builtbyjuls.arat.providers.infrastructure.ProviderRepository providerRepository;

    @Autowired
    private com.builtbyjuls.arat.platform.audit.AuditEventWriter auditEventWriter;

    @Autowired
    private tools.jackson.databind.ObjectMapper objectMapper;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private DataSource dataSource;

    private MockMvc mockMvc;
    private TransactionTemplate transactionTemplate;

    @BeforeEach
    void prepareDatabase() {
        transactionTemplate = new TransactionTemplate(transactionManager);
        removeAuditFailureTrigger();
        jdbcClient.sql("DELETE FROM audit_event").update();
        jdbcClient.sql("DELETE FROM idempotency_record").update();
        jdbcClient.sql("DELETE FROM provider_service_area").update();
        jdbcClient.sql("DELETE FROM provider_supported_category").update();
        jdbcClient.sql("DELETE FROM provider_staff_membership").update();
        jdbcClient.sql("DELETE FROM provider_organization").update();
        jdbcClient.sql("DELETE FROM identity_account WHERE account_id = :accountId")
                .param("accountId", CREATOR_ID)
                .update();
        jdbcClient.sql("INSERT INTO identity_account (account_id, display_name) VALUES (:accountId, 'Provider creator')")
                .param("accountId", CREATOR_ID)
                .update();
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .addFilters(correlationIdFilter)
                .apply(springSecurity())
                .build();
    }

    @AfterEach
    void removeAuditFailureTrigger() {
        jdbcClient.sql("DROP TRIGGER IF EXISTS fail_provider_created_audit ON audit_event").update();
        jdbcClient.sql("DROP FUNCTION IF EXISTS test_fail_provider_created_audit()").update();
    }

    @Test
    @WithAratActor(accountId = "10000000-0000-4000-8000-000000000004")
    void createsOneProviderWithItsAuthenticatedCreatorAsAdmin() throws Exception {
        var response = create("create-provider-1", " BGC Courts ", "[\"KTV\",\"COURT\"]", "[\" Makati \",\"BGC\"]")
                .andExpect(status().isCreated())
                .andExpect(header().string("ETag", "\"1\""))
                .andExpect(header().string("Location", org.hamcrest.Matchers.matchesPattern("/api/v1/providers/[0-9a-f-]{36}")))
                .andExpect(jsonPath("$.displayName").value("BGC Courts"))
                .andExpect(jsonPath("$.supportedCategories[0]").value("COURT"))
                .andExpect(jsonPath("$.supportedCategories[1]").value("KTV"))
                .andExpect(jsonPath("$.serviceAreaCodes[0]").value("BGC"))
                .andExpect(jsonPath("$.serviceAreaCodes[1]").value("Makati"))
                .andExpect(jsonPath("$.verificationStatus").value("UNVERIFIED"))
                .andExpect(jsonPath("$.version").value(1))
                .andReturn()
                .getResponse();

        var providerId = UUID.fromString(objectValue(response.getContentAsString(), "providerId"));
        assertThat(count("SELECT count(*) FROM provider_organization")).isOne();
        assertThat(count("""
                SELECT count(*) FROM provider_staff_membership
                WHERE provider_id = :providerId AND account_id = :accountId
                  AND role = 'ADMIN' AND status = 'ACTIVE'
                """, providerId)).isOne();
        assertThat(count("SELECT count(*) FROM provider_supported_category WHERE provider_id = :providerId", providerId)).isEqualTo(2);
        assertThat(count("SELECT count(*) FROM provider_service_area WHERE provider_id = :providerId", providerId)).isEqualTo(2);
        assertThat(jdbcClient.sql("""
                        SELECT metadata::TEXT FROM audit_event
                        WHERE action = 'provider.created' AND subject_type = 'provider' AND subject_id = :providerId
                        """)
                .param("providerId", providerId)
                .query(String.class)
                .single()).isEqualTo("{\"providerId\": \"%s\"}".formatted(providerId));
    }

    @Test
    @WithAratActor(accountId = "10000000-0000-4000-8000-000000000004")
    void replaysExactlyAndRejectsChangedPayloadWithoutMoreBusinessEffects() throws Exception {
        var first = create("create-provider-replay", "BGC Courts", "[\"COURT\"]", "[\"BGC\"]")
                .andExpect(status().isCreated())
                .andReturn().getResponse();
        var replay = create("create-provider-replay", "BGC Courts", "[\"COURT\"]", "[\"BGC\"]")
                .andExpect(status().isCreated())
                .andExpect(header().string("ETag", first.getHeader("ETag")))
                .andExpect(header().string("Location", first.getHeader("Location")))
                .andReturn().getResponse();
        assertThat(replay.getContentAsString()).isEqualTo(first.getContentAsString());

        create("create-provider-replay", "Different Courts", "[\"COURT\"]", "[\"BGC\"]")
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));

        assertThat(count("SELECT count(*) FROM provider_organization")).isOne();
        assertThat(count("SELECT count(*) FROM provider_staff_membership")).isOne();
        assertThat(count("SELECT count(*) FROM audit_event WHERE action = 'provider.created'")).isOne();
    }

    @Test
    @WithAratActor(accountId = "10000000-0000-4000-8000-000000000004")
    void normalizesTrimmedMaximumLengthFieldsBeforeValidationAndReplay() throws Exception {
        var displayName = " " + "n".repeat(120) + " ";
        var areaCode = " " + "a".repeat(64) + " ";
        var first = create("create-provider-trimmed", displayName, "[\"COURT\"]", "[\"%s\"]".formatted(areaCode))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.displayName").value("n".repeat(120)))
                .andExpect(jsonPath("$.serviceAreaCodes[0]").value("a".repeat(64)))
                .andReturn().getResponse();
        var replay = create("create-provider-trimmed", "n".repeat(120), "[\"COURT\"]", "[\"%s\"]".formatted("a".repeat(64)))
                .andExpect(status().isCreated())
                .andExpect(header().string("ETag", first.getHeader("ETag")))
                .andReturn().getResponse();

        assertThat(replay.getContentAsString()).isEqualTo(first.getContentAsString());
        assertThat(count("SELECT count(*) FROM provider_organization")).isOne();
    }

    @Test
    @WithAratActor(accountId = "10000000-0000-4000-8000-000000000004")
    void mapsMissingIdentityFieldAndProfileValidationToProblemResponses() throws Exception {
        create("create-provider-invalid", "BGC Courts", "[]", "[\"BGC\"]")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        mockMvc.perform(post("/api/v1/providers")
                        .header("Idempotency-Key", "create-provider-actor-in-body")
                        .header(CorrelationIdFilter.HEADER_NAME, CORRELATION_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"actorId\":\"%s\",\"displayName\":\"BGC Courts\",\"supportedCategories\":[\"COURT\"],\"serviceAreaCodes\":[\"BGC\"]}".formatted(CREATOR_ID)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
    }

    @Test
    @WithAratActor(accountId = "10000000-0000-4000-8000-000000000004")
    void rollsBackProviderChildrenAuditAndIdempotencyRecordWhenAuditFails() throws Exception {
        jdbcClient.sql("""
                        CREATE FUNCTION test_fail_provider_created_audit()
                        RETURNS trigger
                        LANGUAGE plpgsql
                        AS $$
                        BEGIN
                            RAISE EXCEPTION 'forced audit failure';
                        END;
                        $$
                        """).update();
        jdbcClient.sql("""
                        CREATE TRIGGER fail_provider_created_audit
                        BEFORE INSERT ON audit_event
                        FOR EACH ROW
                        WHEN (NEW.action = 'provider.created')
                        EXECUTE FUNCTION test_fail_provider_created_audit()
                        """).update();

        create("create-provider-rollback", "BGC Courts", "[\"COURT\"]", "[\"BGC\"]")
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"));

        assertThat(count("SELECT count(*) FROM provider_organization")).isZero();
        assertThat(count("SELECT count(*) FROM provider_staff_membership")).isZero();
        assertThat(count("SELECT count(*) FROM provider_supported_category")).isZero();
        assertThat(count("SELECT count(*) FROM provider_service_area")).isZero();
        assertThat(count("SELECT count(*) FROM audit_event")).isZero();
        assertThat(count("SELECT count(*) FROM idempotency_record")).isZero();
    }

    @Test
    void concurrentExactRetriesCreateOneProviderAndReturnTheSameResource() throws Exception {
        var command = new CreateProviderCommand(
                CREATOR_ID,
                "create-provider-concurrent",
                "BGC Courts",
                List.of(ProviderCategory.COURT),
                List.of("BGC"),
                CORRELATION_ID);
        var firstClaimed = new CountDownLatch(1);
        var releaseFirst = new CountDownLatch(1);
        var secondStarted = new CountDownLatch(1);
        var pausingRepository = new com.builtbyjuls.arat.platform.idempotency.IdempotencyRepository(jdbcClient, objectMapper) {
            @Override
            public com.builtbyjuls.arat.platform.idempotency.ClaimResult claim(
                    com.builtbyjuls.arat.platform.idempotency.IdempotencyScope scope,
                    com.builtbyjuls.arat.platform.idempotency.RequestFingerprint fingerprint) {
                var claim = super.claim(scope, fingerprint);
                if (claim instanceof com.builtbyjuls.arat.platform.idempotency.ClaimResult.Claimed) {
                    firstClaimed.countDown();
                    await(releaseFirst);
                }
                return claim;
            }
        };
        var pausingService = new ProviderCreationService(
                providerRepository, pausingRepository, auditEventWriter, objectMapper);

        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> createInTransaction(pausingService, "provider-create-first", command));
            assertThat(firstClaimed.await(10, TimeUnit.SECONDS)).isTrue();
            var second = executor.submit(() -> {
                secondStarted.countDown();
                return createInTransaction(pausingService, "provider-create-second", command);
            });
            assertThat(secondStarted.await(10, TimeUnit.SECONDS)).isTrue();
            awaitIdempotencyClaimWait();
            releaseFirst.countDown();

            var results = List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));

            assertThat(results).containsOnly(results.getFirst());
        }
        assertThat(count("SELECT count(*) FROM provider_organization")).isOne();
        assertThat(count("SELECT count(*) FROM provider_staff_membership")).isOne();
        assertThat(count("SELECT count(*) FROM audit_event WHERE action = 'provider.created'")).isOne();
        assertThat(count("SELECT count(*) FROM idempotency_record WHERE operation = 'providers.create' AND state = 'COMPLETED'"))
                .isOne();
    }

    @Test
    void publishesTheCreateProviderEndpointInExecutableOpenApi() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/v1/providers'].post.operationId").value("createProvider"))
                .andExpect(jsonPath("$.paths['/api/v1/providers'].post.responses['201'].headers.ETag").exists())
                .andExpect(jsonPath("$.paths['/api/v1/providers'].post.responses['201'].headers.Location").exists())
                .andExpect(jsonPath("$.paths['/api/v1/providers'].post.responses['400'].content['application/problem+json'].schema.$ref")
                        .value("#/components/schemas/ApiProblemResponse"))
                .andExpect(jsonPath("$.paths['/api/v1/providers'].post.responses['401'].content['application/problem+json'].schema.$ref")
                        .value("#/components/schemas/ApiProblemResponse"))
                .andExpect(jsonPath("$.paths['/api/v1/providers'].post.responses['409'].content['application/problem+json'].schema.$ref")
                        .value("#/components/schemas/ApiProblemResponse"))
                .andExpect(jsonPath("$.paths['/api/v1/providers'].post.responses['422'].content['application/problem+json'].schema.$ref")
                        .value("#/components/schemas/ApiProblemResponse"));
    }

    private org.springframework.test.web.servlet.ResultActions create(
            String key, String displayName, String supportedCategories, String serviceAreaCodes) throws Exception {
        return mockMvc.perform(post("/api/v1/providers")
                .header("Idempotency-Key", key)
                .header(CorrelationIdFilter.HEADER_NAME, CORRELATION_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"displayName\":\"%s\",\"supportedCategories\":%s,\"serviceAreaCodes\":%s}"
                        .formatted(displayName, supportedCategories, serviceAreaCodes)));
    }

    private ProviderRepresentation createInTransaction(
            ProviderCreationService service, String applicationName, CreateProviderCommand command) {
        return transactionTemplate.execute(status -> {
            configureTransaction(applicationName);
            return service.create(command);
        });
    }

    private void configureTransaction(String applicationName) {
        var connection = DataSourceUtils.getConnection(dataSource);
        try (var statement = connection.createStatement()) {
            statement.execute("SET LOCAL application_name = '" + applicationName + "'");
        } catch (java.sql.SQLException exception) {
            throw new IllegalStateException("could not configure provider creation transaction", exception);
        }
    }

    private void awaitIdempotencyClaimWait() {
        var deadline = java.time.Instant.now().plusSeconds(10);
        while (java.time.Instant.now().isBefore(deadline)) {
            try (var connection = dataSource.getConnection();
                    var statement = connection.prepareStatement("""
                            SELECT EXISTS (
                                SELECT 1 FROM pg_stat_activity
                                WHERE application_name = 'provider-create-second'
                                  AND wait_event_type = 'Lock'
                            )
                            """)) {
                try (var result = statement.executeQuery()) {
                    result.next();
                    if (result.getBoolean(1)) {
                        return;
                    }
                }
            } catch (java.sql.SQLException exception) {
                throw new IllegalStateException("could not inspect provider creation claim wait", exception);
            }
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(10));
        }
        throw new IllegalStateException("second provider creation did not wait on the idempotency claim");
    }

    private void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("provider creation race was not resumed");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("provider creation race was interrupted", exception);
        }
    }

    private String objectValue(String json, String name) throws Exception {
        return new tools.jackson.databind.ObjectMapper().readTree(json).path(name).asString();
    }

    private long count(String sql) {
        return jdbcClient.sql(sql).query(Long.class).single();
    }

    private long count(String sql, UUID providerId) {
        return jdbcClient.sql(sql)
                .param("providerId", providerId)
                .param("accountId", CREATOR_ID)
                .query(Long.class)
                .single();
    }
}

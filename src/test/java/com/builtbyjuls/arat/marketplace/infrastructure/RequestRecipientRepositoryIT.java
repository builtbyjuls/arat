package com.builtbyjuls.arat.marketplace.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.builtbyjuls.arat.PostgreSqlIntegrationTest;
import com.builtbyjuls.arat.marketplace.domain.RequestRecipient;
import com.builtbyjuls.arat.marketplace.domain.RequestRecipientAccessState;
import com.builtbyjuls.arat.marketplace.domain.RequestRecipientSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
@ActiveProfiles("test")
class RequestRecipientRepositoryIT extends PostgreSqlIntegrationTest {

    private static final UUID OWNER_ID = UUID.fromString("81000000-0000-4000-8000-000000000001");
    private static final UUID GROUP_ID = UUID.fromString("82000000-0000-4000-8000-000000000001");
    private static final UUID PROVIDER_ID = UUID.fromString("83000000-0000-4000-8000-000000000001");
    private static final OffsetDateTime PUBLISHED_AT = OffsetDateTime.parse("2026-09-23T01:00:00Z");

    @Autowired
    private RequestRecipientRepository repository;

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private DataSource dataSource;

    private TransactionTemplate transactionTemplate;

    @BeforeEach
    void prepareInfrastructure() {
        transactionTemplate = new TransactionTemplate(transactionManager);
        jdbcClient.sql("TRUNCATE TABLE marketplace_request_recipient").update();
        jdbcClient.sql("""
                        INSERT INTO identity_account (account_id, display_name)
                        VALUES (:ownerId, 'Recipient owner')
                        ON CONFLICT (account_id) DO NOTHING
                        """)
                .param("ownerId", OWNER_ID)
                .update();
        jdbcClient.sql("""
                        INSERT INTO group_account (group_id, name, description, status, created_by_account_id)
                        VALUES (:groupId, 'Recipient group', '', 'ACTIVE', :ownerId)
                        ON CONFLICT (group_id) DO NOTHING
                        """)
                .param("groupId", GROUP_ID)
                .param("ownerId", OWNER_ID)
                .update();
        jdbcClient.sql("""
                        INSERT INTO provider_organization (
                            provider_id, display_name, status, verification_status, version, eligibility_version
                        )
                        VALUES (:providerId, 'Recipient provider', 'ACTIVE', 'VERIFIED', 4, 4)
                        ON CONFLICT (provider_id) DO UPDATE
                        SET display_name = EXCLUDED.display_name,
                            status = EXCLUDED.status,
                            verification_status = EXCLUDED.verification_status,
                            version = EXCLUDED.version,
                            eligibility_version = EXCLUDED.eligibility_version
                        """)
                .param("providerId", PROVIDER_ID)
                .update();
    }

    @Test
    void persistsRecipientsAndReadsTheActiveProviderFeedInDocumentedOrder() {
        var earlierRequestId = createRequest();
        var laterRequestId = createRequest();
        var earlier = recipient(earlierRequestId, PUBLISHED_AT.plusMinutes(1));
        var later = recipient(laterRequestId, PUBLISHED_AT.plusMinutes(2));

        inTransaction(() -> {
            repository.insert(earlier);
            repository.insert(later);
            return null;
        });

        assertThat(repository.findByRequestId(earlierRequestId)).containsExactly(earlier);
        assertThat(repository.findActiveByProviderId(PROVIDER_ID)).containsExactly(later, earlier);
    }

    @Test
    void enforcesRecipientConstraintsAndCrossReferencesInPostgreSql() {
        var requestId = createRequest();
        insertRow(requestId, PROVIDER_ID, 4, "MATCH_RULE", null, "ACTIVE");

        assertConstraintViolation("marketplace_request_recipient_pkey", () ->
                insertRow(requestId, PROVIDER_ID, 4, "MATCH_RULE", null, "ACTIVE"));
        assertConstraintViolation("marketplace_request_recipient_eligibility_version_positive", () ->
                insertRow(createRequest(), PROVIDER_ID, 0, "MATCH_RULE", null, "ACTIVE"));
        assertConstraintViolation("marketplace_request_recipient_source_check", () ->
                insertRow(createRequest(), PROVIDER_ID, 4, "OTHER", null, "ACTIVE"));
        assertConstraintViolation("marketplace_request_recipient_source_listing_consistency", () ->
                insertRow(createRequest(), PROVIDER_ID, 4, "MATCH_RULE", UUID.randomUUID(), "ACTIVE"));
        assertConstraintViolation("marketplace_request_recipient_source_check", () ->
                insertRow(createRequest(), PROVIDER_ID, 4, "LISTING_INVITATION", null, "ACTIVE"));
        assertConstraintViolation("marketplace_request_recipient_published_request_id_fkey", () ->
                insertRow(UUID.randomUUID(), PROVIDER_ID, 4, "MATCH_RULE", null, "ACTIVE"));
        assertConstraintViolation("marketplace_request_recipient_provider_id_fkey", () ->
                insertRow(createRequest(), UUID.randomUUID(), 4, "MATCH_RULE", null, "ACTIVE"));
    }

    @Test
    void rejectsDirectRecipientMutationAndDeletion() {
        var requestId = createRequest();
        insertRow(requestId, PROVIDER_ID, 4, "MATCH_RULE", null, "ACTIVE");

        assertAppendOnlyViolation(() -> jdbcClient.sql("""
                        UPDATE marketplace_request_recipient
                        SET provider_eligibility_version = 5
                        WHERE published_request_id = :requestId
                          AND provider_id = :providerId
                        """)
                .param("requestId", requestId)
                .param("providerId", PROVIDER_ID)
                .update());
        assertAppendOnlyViolation(() -> jdbcClient.sql("""
                        DELETE FROM marketplace_request_recipient
                        WHERE published_request_id = :requestId
                          AND provider_id = :providerId
                        """)
                .param("requestId", requestId)
                .param("providerId", PROVIDER_ID)
                .update());
    }

    @Test
    void createsProviderFeedAndRequestLookupIndexes() {
        var providerFeedIndex = indexDefinition("marketplace_request_recipient_provider_feed_idx");
        var requestLookupIndex = indexDefinition("marketplace_request_recipient_request_lookup_idx");

        assertThat(providerFeedIndex)
                .contains("provider_id, created_at DESC, published_request_id DESC")
                .contains("access_state")
                .contains("'ACTIVE'");
        assertThat(requestLookupIndex).contains("published_request_id, created_at DESC, provider_id");
    }

    @Test
    void providerFeedQueryUsesTheRecipientFeedIndex() {
        var requestId = createRequest();
        insertRow(requestId, PROVIDER_ID, 4, "MATCH_RULE", null, "ACTIVE");

        List<List<String>> plans = transactionTemplate.execute(status -> {
            jdbcClient.sql("SET LOCAL enable_seqscan = off").update();
            var recipientPlan = jdbcClient.sql("""
                            EXPLAIN (COSTS OFF)
                            SELECT published_request_id
                            FROM marketplace_request_recipient
                            WHERE provider_id = :providerId
                              AND provider_eligibility_version = 4
                              AND access_state = 'ACTIVE'
                            ORDER BY created_at DESC, published_request_id DESC
                            LIMIT 2
                            """)
                    .param("providerId", PROVIDER_ID)
                    .query(String.class)
                    .list();
            var requestPlan = jdbcClient.sql("""
                            EXPLAIN (COSTS OFF)
                            SELECT request_id
                            FROM planning_published_request
                            WHERE request_id = :requestId
                            """)
                    .param("requestId", requestId)
                    .query(String.class)
                    .list();
            return List.of(recipientPlan, requestPlan);
        });

        assertThat(plans.getFirst()).anyMatch(line -> line.contains("marketplace_request_recipient_provider_feed_idx"));
        assertThat(plans.get(1)).anyMatch(line -> line.contains("planning_published_request_pkey"));
    }

    @Test
    void providerRootAndRealRecipientForeignKeyLocksRemainCompatible() throws Exception {
        var firstRequestId = createRequest();
        try (var providerLockOwner = dataSource.getConnection()) {
            providerLockOwner.setAutoCommit(false);
            configureConnection(providerLockOwner, "recipient-provider-lock-owner");
            selectProviderForNoKeyUpdate(providerLockOwner);

            runCompatibleWorker("recipient insertion", () -> {
                try (var recipientConnection = dataSource.getConnection()) {
                    recipientConnection.setAutoCommit(false);
                    configureConnection(recipientConnection, "recipient-insert-worker");
                    insertRecipient(recipientConnection, firstRequestId);
                    recipientConnection.commit();
                    return null;
                }
            });
            providerLockOwner.rollback();
        }

        var secondRequestId = createRequest();
        try (var recipientLockOwner = dataSource.getConnection()) {
            recipientLockOwner.setAutoCommit(false);
            configureConnection(recipientLockOwner, "recipient-lock-owner");
            insertRecipient(recipientLockOwner, secondRequestId);

            runCompatibleWorker("provider mutation", () -> {
                try (var providerConnection = dataSource.getConnection()) {
                    providerConnection.setAutoCommit(false);
                    configureConnection(providerConnection, "provider-update-worker");
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
    }

    private RequestRecipient recipient(UUID requestId, OffsetDateTime createdAt) {
        return new RequestRecipient(
                requestId,
                PROVIDER_ID,
                4,
                RequestRecipientSource.MATCH_RULE,
                null,
                RequestRecipientAccessState.ACTIVE,
                createdAt);
    }

    private UUID createRequest() {
        var planId = UUID.randomUUID();
        var requestId = UUID.randomUUID();
        jdbcClient.sql("""
                        INSERT INTO planning_plan (plan_id, group_id, title, state, created_by_account_id)
                        VALUES (:planId, :groupId, 'Recipient plan', 'COLLABORATING', :ownerId)
                        """)
                .param("planId", planId)
                .param("groupId", GROUP_ID)
                .param("ownerId", OWNER_ID)
                .update();
        jdbcClient.sql("""
                        INSERT INTO planning_published_request (
                            request_id, plan_id, request_version, state, distribution_mode,
                            category, time_zone, area_code, radius_km, requested_starts_at,
                            requested_ends_at, minimum_headcount, maximum_headcount, must_haves,
                            category_attributes, offer_deadline, published_by_account_id, published_at, closed_at
                        )
                        VALUES (
                            :requestId, :planId, 1, 'CLOSED', 'MATCHED_POOL',
                            'COURT', 'Asia/Manila', 'BGC', 5, :startsAt,
                            :endsAt, 4, 10, ARRAY['parking']::varchar[],
                            '{}'::jsonb, :offerDeadline, :ownerId, :publishedAt, :closedAt
                        )
                        """)
                .param("requestId", requestId)
                .param("planId", planId)
                .param("startsAt", PUBLISHED_AT.plusDays(2))
                .param("endsAt", PUBLISHED_AT.plusDays(2).plusHours(2))
                .param("offerDeadline", PUBLISHED_AT.plusDays(1))
                .param("ownerId", OWNER_ID)
                .param("publishedAt", PUBLISHED_AT)
                .param("closedAt", PUBLISHED_AT.plusHours(1))
                .update();
        return requestId;
    }

    private void insertRow(
            UUID requestId,
            UUID providerId,
            long eligibilityVersion,
            String source,
            UUID sourceListingId,
            String accessState) {
        jdbcClient.sql("""
                        INSERT INTO marketplace_request_recipient (
                            published_request_id, provider_id, provider_eligibility_version,
                            source, source_listing_id, access_state
                        )
                        VALUES (
                            :requestId, :providerId, :eligibilityVersion,
                            :source, :sourceListingId, :accessState
                        )
                        """)
                .param("requestId", requestId)
                .param("providerId", providerId)
                .param("eligibilityVersion", eligibilityVersion)
                .param("source", source)
                .param("sourceListingId", sourceListingId)
                .param("accessState", accessState)
                .update();
    }

    private String indexDefinition(String indexName) {
        return jdbcClient.sql("""
                        SELECT indexdef
                        FROM pg_indexes
                        WHERE schemaname = current_schema()
                          AND indexname = :indexName
                        """)
                .param("indexName", indexName)
                .query(String.class)
                .single();
    }

    private void assertConstraintViolation(String constraintName, Runnable operation) {
        assertThatThrownBy(operation::run)
                .isInstanceOfSatisfying(DataAccessException.class, exception ->
                        assertThat(exception.getMostSpecificCause().getMessage()).contains(constraintName));
    }

    private void assertAppendOnlyViolation(Runnable operation) {
        assertThatThrownBy(operation::run)
                .isInstanceOfSatisfying(DataAccessException.class, exception ->
                        assertThat(exception.getMostSpecificCause().getMessage())
                                .contains("request recipient grants are append-only"));
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

    private void insertRecipient(Connection connection, UUID requestId) throws SQLException {
        try (var statement = connection.prepareStatement("""
                INSERT INTO marketplace_request_recipient (
                    published_request_id, provider_id, provider_eligibility_version, source, access_state
                )
                VALUES (?, ?, 4, 'MATCH_RULE', 'ACTIVE')
                """)) {
            statement.setObject(1, requestId);
            statement.setObject(2, PROVIDER_ID);
            assertThat(statement.executeUpdate()).isOne();
        }
    }

    private <T> T runCompatibleWorker(String operation, Callable<T> work) throws Exception {
        var executor = Executors.newSingleThreadExecutor(
                Thread.ofPlatform().daemon(true).name("recipient-lock-compatibility-worker").factory());
        var result = executor.submit(work);
        try {
            return result.get(Duration.ofSeconds(5).toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException exception) {
            throw new AssertionError(operation + " did not progress while the compatible lock was held", exception);
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

    private <T> T inTransaction(Callable<T> work) {
        return transactionTemplate.execute(status -> {
            try {
                return work.call();
            } catch (RuntimeException exception) {
                throw exception;
            } catch (Exception exception) {
                throw new IllegalStateException(exception);
            }
        });
    }
}

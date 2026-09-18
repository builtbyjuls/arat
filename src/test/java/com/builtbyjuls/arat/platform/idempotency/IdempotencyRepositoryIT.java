package com.builtbyjuls.arat.platform.idempotency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.builtbyjuls.arat.PostgreSqlIntegrationTest;
import tools.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(properties = "arat.test.idempotency-context=true")
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class IdempotencyRepositoryIT extends PostgreSqlIntegrationTest {

    private static final UUID ACTOR_ID = UUID.fromString("10000000-0000-4000-8000-000000000010");
    private static final String OPERATION = "groups.create";

    @Autowired
    private IdempotencyRepository repository;

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private TransactionTemplate transactionTemplate;

    @BeforeEach
    void prepareDatabase() {
        transactionTemplate = new TransactionTemplate(transactionManager);
        jdbcClient.sql("DELETE FROM idempotency_record").update();
        jdbcClient.sql("""
                        INSERT INTO identity_account (account_id, display_name)
                        VALUES (:accountId, 'Idempotency test actor')
                        ON CONFLICT (account_id) DO NOTHING
                        """)
                .param("accountId", ACTOR_ID)
                .update();
    }

    @Test
    void claimsAndCompletesARecordWithDatabaseTimeAndSevenDayRetention() {
        var scope = scope("new-key");
        var fingerprint = fingerprint("Friday badminton");

        var claim = inTransaction(() -> repository.claim(scope, fingerprint));
        assertThat(claim).isInstanceOf(ClaimResult.Claimed.class);

        var retentionSeconds = jdbcClient.sql("""
                        SELECT EXTRACT(EPOCH FROM expires_at - created_at)::BIGINT
                        FROM idempotency_record
                        WHERE actor_id = :actorId AND operation = :operation AND idempotency_key = :idempotencyKey
                        """)
                .param("actorId", scope.actorId())
                .param("operation", scope.operation())
                .param("idempotencyKey", scope.idempotencyKey())
                .query(Long.class)
                .single();
        assertThat(retentionSeconds).isEqualTo(7 * 24 * 60 * 60L);

        inTransaction(() -> {
            repository.complete(scope, response(201, "first", Map.of("ETag", "\"1\"")));
            return null;
        });

        var stored = repository.findCompleted(scope).orElseThrow();
        assertThat(stored.status()).isEqualTo(201);
        assertThat(stored.replayState().value().path("result").asText()).isEqualTo("first");
        assertThat(stored.headers().values()).containsExactly(Map.entry("ETag", "\"1\""));
    }

    @Test
    void replaysTheOriginalResponseAfterLaterResourceVersionsAdvance() {
        var scope = scope("replay-key");
        var fingerprint = fingerprint("Friday badminton");
        var original = response(201, "version-one", Map.of("ETag", "\"1\"", "Location", "/api/v1/groups/one"));

        inTransaction(() -> {
            assertThat(repository.claim(scope, fingerprint)).isInstanceOf(ClaimResult.Claimed.class);
            repository.complete(scope, original);
            return null;
        });
        var laterCurrentVersion = "\"2\"";

        var replay = inTransaction(() -> repository.claim(scope, fingerprint));

        assertThat(laterCurrentVersion).isEqualTo("\"2\"");
        assertThat(replay).isInstanceOfSatisfying(ClaimResult.Replay.class, result -> {
            assertThat(result.response().status()).isEqualTo(201);
            assertThat(result.response().replayState().value().path("result").asText()).isEqualTo("version-one");
            assertThat(result.response().headers().values())
                    .containsExactlyInAnyOrderEntriesOf(Map.of("ETag", "\"1\"", "Location", "/api/v1/groups/one"));
        });
    }

    @Test
    void rejectsAReusedKeyWithADifferentFingerprint() {
        var scope = scope("conflicting-key");
        inTransaction(() -> {
            repository.claim(scope, fingerprint("Friday badminton"));
            repository.complete(scope, response(201, "first", Map.of()));
            return null;
        });

        var result = inTransaction(() -> repository.claim(scope, fingerprint("Saturday KTV")));

        assertThat(result).isInstanceOf(ClaimResult.ConflictingFingerprint.class);
    }

    @Test
    void rollsBackTheClaimAndCompletionWithTheBusinessTransaction() {
        var scope = scope("rolled-back-key");

        transactionTemplate.executeWithoutResult(status -> {
            repository.claim(scope, fingerprint("Friday badminton"));
            repository.complete(scope, response(201, "first", Map.of()));
            status.setRollbackOnly();
        });

        assertThat(repository.findCompleted(scope)).isEmpty();
        assertThat(inTransaction(() -> repository.claim(scope, fingerprint("Friday badminton"))))
                .isInstanceOf(ClaimResult.Claimed.class);
    }

    @Test
    void rejectsOversizedReplayStateAndSensitiveFields() {
        var largeState = objectMapper.createObjectNode().put("body", "x".repeat(17000));
        var tokenState = objectMapper.createObjectNode().put("invitationToken", "raw-token");

        assertThatIllegalArgumentException().isThrownBy(() -> ReplayState.from(largeState, objectMapper));
        assertThatIllegalArgumentException().isThrownBy(() -> ReplayState.from(tokenState, objectMapper));
    }

    @Test
    void allowlistsAndBoundsStoredReplayHeaders() {
        assertThat(StoredReplayHeaders.from(Map.of("etag", "\"1\"", "location", "/groups/one")).values())
                .containsExactlyInAnyOrderEntriesOf(Map.of("ETag", "\"1\"", "Location", "/groups/one"));
        assertThatIllegalArgumentException().isThrownBy(() -> StoredReplayHeaders.from(Map.of("Set-Cookie", "session=value")));
        assertThatIllegalArgumentException().isThrownBy(() -> StoredReplayHeaders.from(Map.of("ETag", "x".repeat(2100))));
    }

    @Test
    void allowsOnlyOneConcurrentSameKeyClaimToOwnTheEffect() throws Exception {
        var scope = scope("concurrent-key");
        var fingerprint = fingerprint("Friday badminton");
        var barrier = new CyclicBarrier(2);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var futures = List.of(
                    executor.submit(() -> concurrentClaim(scope, fingerprint, barrier)),
                    executor.submit(() -> concurrentClaim(scope, fingerprint, barrier)));

            var results = List.of(
                    futures.get(0).get(10, TimeUnit.SECONDS),
                    futures.get(1).get(10, TimeUnit.SECONDS));

            assertThat(results).filteredOn(ClaimResult.Claimed.class::isInstance).hasSize(1);
            assertThat(results).filteredOn(ClaimResult.Replay.class::isInstance).hasSize(1);
        }
        assertThat(repository.findCompleted(scope)).isPresent();
    }

    @Test
    void removesExpiredRecordsInBoundedBatches() {
        var scope = scope("expired-key");
        jdbcClient.sql("""
                        INSERT INTO idempotency_record (
                            actor_id, operation, idempotency_key, request_fingerprint,
                            state, created_at, expires_at
                        )
                        SELECT :actorId, :operation, :idempotencyKey, :fingerprint,
                               'IN_PROGRESS', statement_timestamp() - INTERVAL '8 days',
                               statement_timestamp() - INTERVAL '1 day'
                        """)
                .param("actorId", scope.actorId())
                .param("operation", scope.operation())
                .param("idempotencyKey", scope.idempotencyKey())
                .param("fingerprint", fingerprint("expired").value())
                .update();

        assertThat(repository.cleanupExpired(1)).isEqualTo(1);
        assertThat(inTransaction(() -> repository.claim(scope, fingerprint("expired"))))
                .isInstanceOf(ClaimResult.Claimed.class);
    }

    @Test
    void cleanupBetweenAConflictingInsertAndLookupCreatesADurableReplacementClaim() throws Exception {
        var scope = scope("cleanup-race-key");
        insertExpiredRecord(scope, fingerprint("expired").value());
        var conflictingInsert = new java.util.concurrent.CountDownLatch(1);
        var resumeLookup = new java.util.concurrent.CountDownLatch(1);
        var pausingRepository = new IdempotencyRepository(jdbcClient, objectMapper) {
            @Override
            void afterConflictingInsert() {
                conflictingInsert.countDown();
                try {
                    if (!resumeLookup.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("claim lookup was not resumed");
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("claim lookup was interrupted", exception);
                }
            }
        };
        try (var executor = Executors.newSingleThreadExecutor()) {
            var claim = executor.submit(() -> inTransaction(() -> pausingRepository.claim(scope, fingerprint("expired"))));

            assertThat(conflictingInsert.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(repository.cleanupExpired(1)).isEqualTo(1);
            resumeLookup.countDown();

            assertThat(claim.get(10, TimeUnit.SECONDS)).isInstanceOf(ClaimResult.Claimed.class);
        }
        assertThat(jdbcClient.sql("""
                        SELECT count(*)
                        FROM idempotency_record
                        WHERE actor_id = :actorId
                          AND operation = :operation
                          AND idempotency_key = :idempotencyKey
                          AND expires_at > clock_timestamp()
                        """)
                .param("actorId", scope.actorId())
                .param("operation", scope.operation())
                .param("idempotencyKey", scope.idempotencyKey())
                .query(Long.class)
                .single()).isEqualTo(1);
    }

    private ClaimResult concurrentClaim(IdempotencyScope scope, RequestFingerprint fingerprint, CyclicBarrier barrier) throws Exception {
        barrier.await(10, TimeUnit.SECONDS);
        return inTransaction(() -> {
            var result = repository.claim(scope, fingerprint);
            if (result instanceof ClaimResult.Claimed) {
                repository.complete(scope, response(201, "first", Map.of("ETag", "\"1\"")));
            }
            return result;
        });
    }

    private IdempotencyScope scope(String key) {
        return new IdempotencyScope(ACTOR_ID, OPERATION, key);
    }

    private RequestFingerprint fingerprint(String title) {
        return RequestFingerprint.fromCanonicalFields(Map.of("title", title));
    }

    private CompletedIdempotencyResponse response(int status, String result, Map<String, String> headers) {
        return new CompletedIdempotencyResponse(
                status,
                ReplayState.from(objectMapper.createObjectNode().put("result", result), objectMapper),
                StoredReplayHeaders.from(headers),
                UUID.fromString("20000000-0000-4000-8000-000000000001"));
    }

    private void insertExpiredRecord(IdempotencyScope scope, String fingerprint) {
        jdbcClient.sql("""
                        INSERT INTO idempotency_record (
                            actor_id, operation, idempotency_key, request_fingerprint,
                            state, created_at, expires_at
                        )
                        SELECT :actorId, :operation, :idempotencyKey, :fingerprint,
                               'IN_PROGRESS', statement_timestamp() - INTERVAL '8 days',
                               statement_timestamp() - INTERVAL '1 day'
                        """)
                .param("actorId", scope.actorId())
                .param("operation", scope.operation())
                .param("idempotencyKey", scope.idempotencyKey())
                .param("fingerprint", fingerprint)
                .update();
    }

    private <T> T inTransaction(java.util.concurrent.Callable<T> work) {
        return transactionTemplate.execute(status -> {
            try {
                return work.call();
            } catch (Exception exception) {
                throw new IllegalStateException(exception);
            }
        });
    }
}

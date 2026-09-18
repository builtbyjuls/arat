package com.builtbyjuls.arat.platform.idempotency;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class IdempotencyRepository {

    private final JdbcClient jdbcClient;
    private final ObjectMapper objectMapper;

    public IdempotencyRepository(JdbcClient jdbcClient, ObjectMapper objectMapper) {
        this.jdbcClient = jdbcClient;
        this.objectMapper = objectMapper;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public ClaimResult claim(IdempotencyScope scope, RequestFingerprint fingerprint) {
        while (true) {
            var inserted = jdbcClient.sql("""
                        INSERT INTO idempotency_record (
                            actor_id, operation, idempotency_key, request_fingerprint,
                            state, created_at, expires_at
                        )
                        SELECT :actorId, :operation, :idempotencyKey, :fingerprint,
                               'IN_PROGRESS', statement_timestamp(),
                               statement_timestamp() + INTERVAL '7 days'
                        ON CONFLICT DO NOTHING
                        """)
                .param("actorId", scope.actorId())
                .param("operation", scope.operation())
                .param("idempotencyKey", scope.idempotencyKey())
                .param("fingerprint", fingerprint.value())
                    .update();
            if (inserted == 1) {
                return new ClaimResult.Claimed();
            }
            afterConflictingInsert();

            var existing = jdbcClient.sql("""
                        SELECT request_fingerprint, state, resource_id, response_status,
                               replay_state::TEXT AS replay_state, response_headers::TEXT AS response_headers
                        FROM idempotency_record
                        WHERE actor_id = :actorId
                          AND operation = :operation
                          AND idempotency_key = :idempotencyKey
                        FOR UPDATE
                        """)
                .param("actorId", scope.actorId())
                .param("operation", scope.operation())
                .param("idempotencyKey", scope.idempotencyKey())
                    .query((resultSet, rowNum) -> new StoredRecord(
                        resultSet.getString("request_fingerprint"),
                        resultSet.getString("state"),
                        resultSet.getObject("resource_id", UUID.class),
                        resultSet.getObject("response_status", Integer.class),
                        resultSet.getString("replay_state"),
                            resultSet.getString("response_headers")))
                    .optional();
            if (existing.isEmpty()) {
                continue;
            }
            var lockedRecord = existing.orElseThrow();
            if (!lockedRecord.fingerprint().equals(fingerprint.value())) {
                return new ClaimResult.ConflictingFingerprint();
            }
            if (lockedRecord.state().equals("COMPLETED")) {
                return new ClaimResult.Replay(lockedRecord.asResponse(objectMapper));
            }
            return new ClaimResult.InProgress();
        }
    }

    void afterConflictingInsert() {
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void complete(IdempotencyScope scope, CompletedIdempotencyResponse response) {
        var updated = jdbcClient.sql("""
                        UPDATE idempotency_record
                        SET state = 'COMPLETED',
                            resource_id = :resourceId,
                            response_status = :responseStatus,
                            replay_state = CAST(:replayState AS JSONB),
                            response_headers = CAST(:responseHeaders AS JSONB)
                        WHERE actor_id = :actorId
                          AND operation = :operation
                          AND idempotency_key = :idempotencyKey
                          AND state = 'IN_PROGRESS'
                        """)
                .param("actorId", scope.actorId())
                .param("operation", scope.operation())
                .param("idempotencyKey", scope.idempotencyKey())
                .param("resourceId", response.resourceId())
                .param("responseStatus", response.status())
                .param("replayState", writeJson(response.replayState().value()))
                .param("responseHeaders", response.headers().toJson())
                .update();
        if (updated != 1) {
            throw new IllegalStateException("idempotency record must be claimed before completion");
        }
    }

    @Transactional(readOnly = true)
    public Optional<CompletedIdempotencyResponse> findCompleted(IdempotencyScope scope) {
        return jdbcClient.sql("""
                        SELECT resource_id, response_status, replay_state::TEXT AS replay_state,
                               response_headers::TEXT AS response_headers
                        FROM idempotency_record
                        WHERE actor_id = :actorId
                          AND operation = :operation
                          AND idempotency_key = :idempotencyKey
                          AND state = 'COMPLETED'
                        """)
                .param("actorId", scope.actorId())
                .param("operation", scope.operation())
                .param("idempotencyKey", scope.idempotencyKey())
                .query((resultSet, rowNum) -> new StoredRecord(
                        null,
                        "COMPLETED",
                        resultSet.getObject("resource_id", UUID.class),
                        resultSet.getInt("response_status"),
                        resultSet.getString("replay_state"),
                        resultSet.getString("response_headers")))
                .optional()
                .map(record -> record.asResponse(objectMapper));
    }

    @Transactional
    public int cleanupExpired(int batchSize) {
        if (batchSize < 1 || batchSize > 1000) {
            throw new IllegalArgumentException("batchSize must be between 1 and 1000");
        }
        return jdbcClient.sql("""
                        WITH expired AS (
                            SELECT ctid
                            FROM idempotency_record
                            WHERE expires_at <= clock_timestamp()
                            ORDER BY expires_at
                            FOR UPDATE SKIP LOCKED
                            LIMIT :batchSize
                        )
                        DELETE FROM idempotency_record
                        WHERE ctid IN (SELECT ctid FROM expired)
                        """)
                .param("batchSize", batchSize)
                .update();
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JacksonException exception) {
            throw new IllegalArgumentException("idempotency value cannot be serialized", exception);
        }
    }

    private record StoredRecord(
            String fingerprint,
            String state,
            UUID resourceId,
            Integer responseStatus,
            String replayState,
            String responseHeaders) {

        private CompletedIdempotencyResponse asResponse(ObjectMapper objectMapper) {
            try {
                var headers = objectMapper.readValue(responseHeaders, Map.class);
                @SuppressWarnings("unchecked")
                var typedHeaders = (Map<String, String>) headers;
                return new CompletedIdempotencyResponse(
                        responseStatus,
                        ReplayState.from(objectMapper.readTree(replayState), objectMapper),
                        StoredReplayHeaders.from(typedHeaders),
                        resourceId);
            } catch (JacksonException exception) {
                throw new IllegalStateException("stored idempotency response is invalid", exception);
            }
        }
    }
}

package com.builtbyjuls.arat.messaging.infrastructure;

import com.builtbyjuls.arat.messaging.domain.OutboxEvent;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class OutboxEventRepository {

    private final JdbcClient jdbcClient;

    public OutboxEventRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void insert(OutboxEvent event) {
        jdbcClient.sql("""
                        INSERT INTO messaging_outbox (
                            event_id, business_key, event_type, schema_version, occurred_at,
                            aggregate_type, aggregate_id, aggregate_version, trace_id, payload
                        )
                        VALUES (
                            :eventId, :businessKey, :eventType, :schemaVersion, :occurredAt,
                            :aggregateType, :aggregateId, :aggregateVersion, :traceId, CAST(:payload AS JSONB)
                        )
                        """)
                .param("eventId", event.eventId())
                .param("businessKey", event.businessKey())
                .param("eventType", event.eventType())
                .param("schemaVersion", event.schemaVersion())
                .param("occurredAt", event.occurredAt())
                .param("aggregateType", event.aggregateType())
                .param("aggregateId", event.aggregateId())
                .param("aggregateVersion", event.aggregateVersion())
                .param("traceId", event.traceId())
                .param("payload", event.payload())
                .update();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public boolean insertIfAbsent(OutboxEvent event) {
        return jdbcClient.sql("""
                        INSERT INTO messaging_outbox (
                            event_id, business_key, event_type, schema_version, occurred_at,
                            aggregate_type, aggregate_id, aggregate_version, trace_id, payload
                        )
                        VALUES (
                            :eventId, :businessKey, :eventType, :schemaVersion, :occurredAt,
                            :aggregateType, :aggregateId, :aggregateVersion, :traceId, CAST(:payload AS JSONB)
                        )
                        ON CONFLICT (business_key) DO NOTHING
                        """)
                .param("eventId", event.eventId())
                .param("businessKey", event.businessKey())
                .param("eventType", event.eventType())
                .param("schemaVersion", event.schemaVersion())
                .param("occurredAt", event.occurredAt())
                .param("aggregateType", event.aggregateType())
                .param("aggregateId", event.aggregateId())
                .param("aggregateVersion", event.aggregateVersion())
                .param("traceId", event.traceId())
                .param("payload", event.payload())
                .update() == 1;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public int persistedPayloadSize(String payload) {
        return jdbcClient.sql("""
                        SELECT octet_length(CAST(CAST(:payload AS JSONB) AS TEXT))
                        """)
                .param("payload", payload)
                .query(Integer.class)
                .single();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public boolean payloadsAreEquivalent(String existingPayload, String candidatePayload) {
        return jdbcClient.sql("""
                        SELECT CAST(:existingPayload AS JSONB) = CAST(:candidatePayload AS JSONB)
                        """)
                .param("existingPayload", existingPayload)
                .param("candidatePayload", candidatePayload)
                .query(Boolean.class)
                .single();
    }

    @Transactional(readOnly = true)
    public Optional<OutboxEvent> findById(UUID eventId) {
        return jdbcClient.sql("""
                        SELECT event_id, business_key, event_type, schema_version, occurred_at,
                               aggregate_type, aggregate_id, aggregate_version, trace_id, payload::TEXT
                        FROM messaging_outbox
                        WHERE event_id = :eventId
                        """)
                .param("eventId", eventId)
                .query(this::mapEvent)
                .optional();
    }

    @Transactional(readOnly = true)
    public Optional<OutboxEvent> findByBusinessKey(String businessKey) {
        return jdbcClient.sql("""
                        SELECT event_id, business_key, event_type, schema_version, occurred_at,
                               aggregate_type, aggregate_id, aggregate_version, trace_id, payload::TEXT
                        FROM messaging_outbox
                        WHERE business_key = :businessKey
                        """)
                .param("businessKey", businessKey)
                .query(this::mapEvent)
                .optional();
    }

    private OutboxEvent mapEvent(ResultSet resultSet, int rowNum) throws SQLException {
        return new OutboxEvent(
                resultSet.getObject("event_id", UUID.class),
                resultSet.getString("business_key"),
                resultSet.getString("event_type"),
                resultSet.getLong("schema_version"),
                resultSet.getObject("occurred_at", OffsetDateTime.class),
                resultSet.getString("aggregate_type"),
                resultSet.getObject("aggregate_id", UUID.class),
                resultSet.getLong("aggregate_version"),
                resultSet.getString("trace_id"),
                resultSet.getString("payload"));
    }
}

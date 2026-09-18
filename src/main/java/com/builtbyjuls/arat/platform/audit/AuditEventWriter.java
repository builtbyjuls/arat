package com.builtbyjuls.arat.platform.audit;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
public class AuditEventWriter {

    private final JdbcClient jdbcClient;
    private final ObjectMapper objectMapper;

    public AuditEventWriter(JdbcClient jdbcClient, ObjectMapper objectMapper) {
        this.jdbcClient = jdbcClient;
        this.objectMapper = objectMapper;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void append(AuditEvent event) {
        jdbcClient.sql("""
                        INSERT INTO audit_event (
                            event_id, occurred_at, actor_id, action, subject_type, subject_id,
                            group_id, plan_id, correlation_id, metadata
                        )
                        VALUES (
                            :eventId, statement_timestamp(), :actorId, :action, :subjectType, :subjectId,
                            :groupId, :planId, :correlationId, CAST(:metadata AS JSONB)
                        )
                        """)
                .param("eventId", event.eventId())
                .param("actorId", event.actorId())
                .param("action", event.action())
                .param("subjectType", event.subjectType())
                .param("subjectId", event.subjectId())
                .param("groupId", event.groupId())
                .param("planId", event.planId())
                .param("correlationId", event.correlationId())
                .param("metadata", writeMetadata(event.metadata()))
                .update();
    }

    private String writeMetadata(AuditMetadata metadata) {
        try {
            return objectMapper.writeValueAsString(metadata.references());
        } catch (JacksonException exception) {
            throw new IllegalArgumentException("audit metadata cannot be serialized", exception);
        }
    }
}

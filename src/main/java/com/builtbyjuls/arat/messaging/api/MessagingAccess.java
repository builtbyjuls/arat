package com.builtbyjuls.arat.messaging.api;

import com.builtbyjuls.arat.messaging.domain.OutboxEvent;
import com.builtbyjuls.arat.messaging.infrastructure.OutboxEventRepository;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * Public Messaging boundary for appending durable integration-event intent.
 */
@Component
public class MessagingAccess {

    private static final int MAXIMUM_PAYLOAD_BYTES = 4096;

    private final OutboxEventRepository repository;
    private final ObjectMapper objectMapper;

    public MessagingAccess(OutboxEventRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void append(AppendOutboxEventCommand command) {
        var event = toEvent(command);
        if (repository.insertIfAbsent(event)) {
            return;
        }
        var existing = repository.findByBusinessKey(event.businessKey())
                .orElseThrow(() -> new IllegalStateException("outbox event disappeared after duplicate business key"));
        if (!hasSameImmutableContent(existing, event)) {
            throw new OutboxEventConflictException(event.businessKey());
        }
    }

    private OutboxEvent toEvent(AppendOutboxEventCommand command) {
        if (command == null) {
            throw new IllegalArgumentException("command must not be null");
        }
        var envelope = command.envelope();
        return new OutboxEvent(
                command.eventId(),
                command.businessKey(),
                envelope.eventType(),
                envelope.schemaVersion(),
                databaseTimestamp(envelope.occurredAt()),
                envelope.aggregateType(),
                envelope.aggregateId(),
                envelope.aggregateVersion(),
                envelope.traceId(),
                validatePayload(envelope.payload()));
    }

    private String validatePayload(String payload) {
        try {
            var node = objectMapper.readTree(payload);
            if (node == null || !node.isObject()) {
                throw new IllegalArgumentException("payload must be a JSON object");
            }
            if (repository.persistedPayloadSize(payload) > MAXIMUM_PAYLOAD_BYTES) {
                throw new IllegalArgumentException("payload must be at most " + MAXIMUM_PAYLOAD_BYTES + " bytes");
            }
            return payload;
        } catch (JacksonException exception) {
            throw new IllegalArgumentException("payload must be a JSON object", exception);
        }
    }

    private boolean hasSameImmutableContent(OutboxEvent existing, OutboxEvent event) {
        return existing.eventId().equals(event.eventId())
                && existing.businessKey().equals(event.businessKey())
                && existing.eventType().equals(event.eventType())
                && existing.schemaVersion() == event.schemaVersion()
                && existing.occurredAt().toInstant().equals(event.occurredAt().toInstant())
                && existing.aggregateType().equals(event.aggregateType())
                && existing.aggregateId().equals(event.aggregateId())
                && existing.aggregateVersion() == event.aggregateVersion()
                && existing.traceId().equals(event.traceId())
                && repository.payloadsAreEquivalent(existing.payload(), event.payload());
    }

    private OffsetDateTime databaseTimestamp(OffsetDateTime occurredAt) {
        return OffsetDateTime.ofInstant(
                occurredAt.toInstant().plusNanos(500).truncatedTo(ChronoUnit.MICROS),
                ZoneOffset.UTC);
    }
}

package com.builtbyjuls.arat.messaging.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.builtbyjuls.arat.PostgreSqlIntegrationTest;
import com.builtbyjuls.arat.messaging.domain.OutboxEvent;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(properties = "arat.test.messaging-context=true")
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class OutboxEventRepositoryIT extends PostgreSqlIntegrationTest {

    private static final UUID EVENT_ID = UUID.fromString("10000000-0000-4000-8000-000000000014");
    private static final UUID AGGREGATE_ID = UUID.fromString("20000000-0000-4000-8000-000000000014");

    @Autowired
    private OutboxEventRepository repository;

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private TransactionTemplate transactionTemplate;

    @BeforeEach
    void prepareDatabase() {
        transactionTemplate = new TransactionTemplate(transactionManager);
        jdbcClient.sql("DELETE FROM messaging_outbox").update();
    }

    @Test
    void persistsAndMapsAnImmutablePendingEnvelope() {
        var event = event(EVENT_ID, "ProviderRequestPublished:request:provider");

        inTransaction(() -> {
            repository.insert(event);
            return null;
        });

        assertThat(repository.findById(EVENT_ID)).contains(event);
        assertThat(jdbcClient.sql("""
                        SELECT delivery_status, published_at
                        FROM messaging_outbox
                        WHERE event_id = :eventId
                        """)
                .param("eventId", EVENT_ID)
                .query((resultSet, rowNum) -> resultSet.getString("delivery_status")
                        + ":" + resultSet.getObject("published_at"))
                .single()).isEqualTo("PENDING:null");
    }

    @Test
    void databaseRejectsDuplicateBusinessKeysInvalidPayloadsAndBoundViolations() {
        insert(event(EVENT_ID, "ProviderRequestPublished:request:provider"));

        assertThatThrownBy(() -> insert(event(UUID.randomUUID(), "ProviderRequestPublished:request:provider")))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertRaw(UUID.randomUUID(), "key-not-json", "[]", 1, 1, "trace"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertRaw(UUID.randomUUID(), "key-large-payload", "{\"value\":\"" + "x".repeat(4097) + "\"}", 1, 1, "trace"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertRaw(UUID.randomUUID(), "x".repeat(257), "{}", 1, 1, "trace"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertRaw(UUID.randomUUID(), "key-zero-schema", "{}", 0, 1, "trace"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertRaw(UUID.randomUUID(), "key-zero-aggregate", "{}", 1, 0, "trace"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertRaw(UUID.randomUUID(), "key-blank-trace", "{}", 1, 1, " \t"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void databaseRejectsMutationOfEnvelopeAndPayloadButAllowsFutureDeliveryMetadataUpdates() {
        insert(event(EVENT_ID, "ProviderRequestPublished:request:provider"));

        assertThatThrownBy(() -> jdbcClient.sql("""
                        UPDATE messaging_outbox
                        SET payload = '{"requestId":"changed"}'::JSONB
                        WHERE event_id = :eventId
                        """)
                .param("eventId", EVENT_ID)
                .update()).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbcClient.sql("""
                        UPDATE messaging_outbox
                        SET event_type = 'Changed'
                        WHERE event_id = :eventId
                        """)
                .param("eventId", EVENT_ID)
                .update()).isInstanceOf(DataIntegrityViolationException.class);

        jdbcClient.sql("""
                        UPDATE messaging_outbox
                        SET available_at = statement_timestamp(), published_at = statement_timestamp()
                        WHERE event_id = :eventId
                        """)
                .param("eventId", EVENT_ID)
                .update();

        assertThat(repository.findById(EVENT_ID)).contains(event(EVENT_ID, "ProviderRequestPublished:request:provider"));
    }

    private void insert(OutboxEvent event) {
        inTransaction(() -> {
            repository.insert(event);
            return null;
        });
    }

    private void insertRaw(
            UUID eventId, String businessKey, String payload, long schemaVersion, long aggregateVersion, String traceId) {
        jdbcClient.sql("""
                        INSERT INTO messaging_outbox (
                            event_id, business_key, event_type, schema_version, occurred_at,
                            aggregate_type, aggregate_id, aggregate_version, trace_id, payload
                        ) VALUES (
                            :eventId, :businessKey, 'ProviderRequestPublished', :schemaVersion, statement_timestamp(),
                            'PublishedRequest', :aggregateId, :aggregateVersion, :traceId, CAST(:payload AS JSONB)
                        )
                        """)
                .param("eventId", eventId)
                .param("businessKey", businessKey)
                .param("schemaVersion", schemaVersion)
                .param("aggregateId", AGGREGATE_ID)
                .param("aggregateVersion", aggregateVersion)
                .param("traceId", traceId)
                .param("payload", payload)
                .update();
    }

    private OutboxEvent event(UUID eventId, String businessKey) {
        return new OutboxEvent(
                eventId,
                businessKey,
                "ProviderRequestPublished",
                1,
                OffsetDateTime.parse("2026-09-23T08:00:00Z"),
                "PublishedRequest",
                AGGREGATE_ID,
                7,
                "trace-014",
                "{\"requestId\": \"40000000-0000-4000-8000-000000000014\", \"providerId\": \"30000000-0000-4000-8000-000000000014\"}");
    }

    private <T> T inTransaction(java.util.concurrent.Callable<T> callback) {
        return transactionTemplate.execute(status -> {
            try {
                return callback.call();
            } catch (RuntimeException exception) {
                throw exception;
            } catch (Exception exception) {
                throw new IllegalStateException(exception);
            }
        });
    }
}

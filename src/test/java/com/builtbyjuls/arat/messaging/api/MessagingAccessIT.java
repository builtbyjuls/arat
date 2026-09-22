package com.builtbyjuls.arat.messaging.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.builtbyjuls.arat.PostgreSqlIntegrationTest;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(properties = "arat.test.messaging-access-context=true")
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class MessagingAccessIT extends PostgreSqlIntegrationTest {

    private static final UUID ACCOUNT_ID = UUID.fromString("10000000-0000-4000-8000-000000000012");
    private static final UUID EVENT_ID = UUID.fromString("20000000-0000-4000-8000-000000000012");
    private static final UUID AGGREGATE_ID = UUID.fromString("30000000-0000-4000-8000-000000000012");

    @Autowired
    private MessagingAccess messagingAccess;

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private TransactionTemplate transactionTemplate;

    @BeforeEach
    void prepareDatabase() {
        transactionTemplate = new TransactionTemplate(transactionManager);
        jdbcClient.sql("DELETE FROM messaging_outbox").update();
        jdbcClient.sql("DELETE FROM identity_account WHERE account_id = :accountId")
                .param("accountId", ACCOUNT_ID)
                .update();
    }

    @Test
    void rejectsAppendOutsideACallerTransaction() {
        assertThatThrownBy(() -> messagingAccess.append(event()))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    void commitsCallerStateAndEventTogether() {
        var event = event();

        transactionTemplate.executeWithoutResult(status -> {
            createCallerState();
            messagingAccess.append(event);
        });

        assertThat(accountCount()).isEqualTo(1);
        assertThat(outboxCount()).isEqualTo(1);
    }

    @Test
    void rollsBackCallerStateAndEventTogether() {
        transactionTemplate.executeWithoutResult(status -> {
            createCallerState();
            messagingAccess.append(event());
            status.setRollbackOnly();
        });

        assertThat(accountCount()).isZero();
        assertThat(outboxCount()).isZero();
    }

    @Test
    void acceptsADuplicateWithIdenticalImmutableContent() {
        var event = event();

        transactionTemplate.executeWithoutResult(status -> messagingAccess.append(event));
        transactionTemplate.executeWithoutResult(status -> messagingAccess.append(event));

        assertThat(outboxCount()).isEqualTo(1);
    }

    @Test
    void acceptsADuplicateWhenPostgreSqlRoundsOccurrenceTimeToMicroseconds() {
        var event = eventWithOccurredAt(OffsetDateTime.parse("2026-09-23T08:00:00.123456789Z"));

        transactionTemplate.executeWithoutResult(status -> messagingAccess.append(event));
        transactionTemplate.executeWithoutResult(status -> messagingAccess.append(event));

        assertThat(outboxCount()).isEqualTo(1);
        assertThat(jdbcClient.sql("SELECT occurred_at FROM messaging_outbox WHERE event_id = :eventId")
                .param("eventId", EVENT_ID)
                .query(OffsetDateTime.class)
                .single()).isEqualTo(OffsetDateTime.parse("2026-09-23T08:00:00.123457Z"));
    }

    @Test
    void acceptsAnExponentFormPayloadOnExactReplay() {
        var event = eventWithPayload("{\"value\":1e20}");

        transactionTemplate.executeWithoutResult(status -> messagingAccess.append(event));
        transactionTemplate.executeWithoutResult(status -> messagingAccess.append(event));

        assertThat(outboxCount()).isEqualTo(1);
    }

    @Test
    void rejectsADuplicateBusinessKeyWithConflictingImmutableContent() {
        transactionTemplate.executeWithoutResult(status ->
                messagingAccess.append(event()));

        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status ->
                messagingAccess.append(eventWithPayload("{\"requestId\":\"different\"}"))))
                .isInstanceOf(OutboxEventConflictException.class);

        assertThat(outboxCount()).isEqualTo(1);
    }

    @Test
    void rejectsHighPrecisionDecimalContentThatConflictsWithAnExistingBusinessKey() {
        transactionTemplate.executeWithoutResult(status ->
                messagingAccess.append(eventWithPayload("{\"value\":1.00000000000000001}")));

        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status ->
                messagingAccess.append(eventWithPayload("{\"value\":1.0}"))))
                .isInstanceOf(OutboxEventConflictException.class);

        assertThat(outboxCount()).isEqualTo(1);
    }

    @Test
    void rejectsPayloadsThatAreNotBoundedJsonObjects() {
        assertThatIllegalArgumentException().isThrownBy(() -> transactionTemplate.executeWithoutResult(status ->
                messagingAccess.append(new AppendOutboxEventCommand(
                        EVENT_ID,
                        "ProviderRequestPublished:request:provider",
                        envelope("[]")))))
                .withMessage("payload must be a JSON object");

        assertThatIllegalArgumentException().isThrownBy(() -> transactionTemplate.executeWithoutResult(status ->
                messagingAccess.append(new AppendOutboxEventCommand(
                        EVENT_ID,
                        "ProviderRequestPublished:request:provider",
                        envelope("{\"value\":\"" + "x".repeat(4084) + "\"}")))))
                .withMessage("payload must be at most 4096 bytes");
    }

    @Test
    void acceptsTheLargestPayloadThatPostgreSqlStoresWithinTheBound() {
        transactionTemplate.executeWithoutResult(status ->
                messagingAccess.append(new AppendOutboxEventCommand(
                        EVENT_ID,
                        "ProviderRequestPublished:request:provider",
                        envelope("{\"value\":\"" + "x".repeat(4083) + "\"}"))));

        assertThat(outboxCount()).isEqualTo(1);
    }

    private AppendOutboxEventCommand event() {
        return eventWithPayload("{\"requestId\":\"40000000-0000-4000-8000-000000000012\"}");
    }

    private AppendOutboxEventCommand eventWithPayload(String payload) {
        return eventWithOccurredAtAndPayload(OffsetDateTime.parse("2026-09-23T08:00:00Z"), payload);
    }

    private AppendOutboxEventCommand eventWithOccurredAt(OffsetDateTime occurredAt) {
        return eventWithOccurredAtAndPayload(occurredAt, "{\"requestId\":\"40000000-0000-4000-8000-000000000012\"}");
    }

    private AppendOutboxEventCommand eventWithOccurredAtAndPayload(OffsetDateTime occurredAt, String payload) {
        return new AppendOutboxEventCommand(
                EVENT_ID,
                "ProviderRequestPublished:request:provider",
                envelope(occurredAt, payload));
    }

    private OutboxEventEnvelope envelope(String payload) {
        return envelope(OffsetDateTime.parse("2026-09-23T08:00:00Z"), payload);
    }

    private OutboxEventEnvelope envelope(OffsetDateTime occurredAt, String payload) {
        return new OutboxEventEnvelope(
                "ProviderRequestPublished",
                1,
                occurredAt,
                "PublishedRequest",
                AGGREGATE_ID,
                7,
                "trace-012",
                payload);
    }

    private void createCallerState() {
        jdbcClient.sql("INSERT INTO identity_account (account_id, display_name) VALUES (:accountId, 'Messaging test actor')")
                .param("accountId", ACCOUNT_ID)
                .update();
    }

    private long accountCount() {
        return jdbcClient.sql("SELECT count(*) FROM identity_account WHERE account_id = :accountId")
                .param("accountId", ACCOUNT_ID)
                .query(Long.class)
                .single();
    }

    private long outboxCount() {
        return jdbcClient.sql("SELECT count(*) FROM messaging_outbox")
                .query(Long.class)
                .single();
    }
}

package com.builtbyjuls.arat.platform.audit;

import static org.assertj.core.api.Assertions.assertThat;

import com.builtbyjuls.arat.PostgreSqlIntegrationTest;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(properties = "arat.test.audit-context=true")
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class AuditEventWriterIT extends PostgreSqlIntegrationTest {

    private static final UUID ACTOR_ID = UUID.fromString("10000000-0000-4000-8000-000000000020");
    private static final UUID SUBJECT_ID = UUID.fromString("20000000-0000-4000-8000-000000000020");

    @Autowired
    private AuditEventWriter writer;

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private TransactionTemplate transactionTemplate;

    @BeforeEach
    void prepareDatabase() {
        transactionTemplate = new TransactionTemplate(transactionManager);
        jdbcClient.sql("DELETE FROM audit_event").update();
        jdbcClient.sql("DELETE FROM identity_account WHERE account_id = :actorId")
                .param("actorId", ACTOR_ID)
                .update();
    }

    @Test
    void commitsTheSampleStateAndAuditEventTogether() {
        var event = event(ACTOR_ID);

        transactionTemplate.executeWithoutResult(status -> {
            createSampleState(ACTOR_ID);
            writer.append(event);
        });

        assertThat(accountCount(ACTOR_ID)).isEqualTo(1);
        assertThat(auditCount(event.eventId())).isEqualTo(1);
        assertThat(jdbcClient.sql("SELECT metadata::TEXT FROM audit_event WHERE event_id = :eventId")
                .param("eventId", event.eventId())
                .query(String.class)
                .single()).isEqualTo("{\"relatedSubjectId\": \"30000000-0000-4000-8000-000000000020\"}");
    }

    @Test
    void rollsBackTheSampleStateAndAuditEventTogether() {
        var rolledBackActorId = UUID.fromString("10000000-0000-4000-8000-000000000021");
        var event = event(rolledBackActorId);

        transactionTemplate.executeWithoutResult(status -> {
            createSampleState(rolledBackActorId);
            writer.append(event);
            status.setRollbackOnly();
        });

        assertThat(accountCount(rolledBackActorId)).isZero();
        assertThat(auditCount(event.eventId())).isZero();
    }

    @Test
    void persistsTheLargestMetadataAllowedByTheApi() {
        var references = new java.util.HashMap<String, UUID>();
        for (var index = 0; index < 18; index++) {
            references.put("reference" + "x".repeat(53) + index, UUID.randomUUID());
        }
        var event = event(ACTOR_ID, AuditMetadata.references(references));

        transactionTemplate.executeWithoutResult(status -> {
            createSampleState(ACTOR_ID);
            writer.append(event);
        });

        assertThat(auditCount(event.eventId())).isEqualTo(1);
    }

    private AuditEvent event(UUID actorId) {
        return event(actorId, AuditMetadata.references(Map.of(
                "relatedSubjectId", UUID.fromString("30000000-0000-4000-8000-000000000020"))));
    }

    private AuditEvent event(UUID actorId, AuditMetadata metadata) {
        return new AuditEvent(
                UUID.randomUUID(),
                actorId,
                "provider.suspended",
                "provider",
                SUBJECT_ID,
                UUID.fromString("40000000-0000-4000-8000-000000000020"),
                UUID.fromString("50000000-0000-4000-8000-000000000020"),
                "test-correlation-id",
                metadata);
    }

    private void createSampleState(UUID actorId) {
        jdbcClient.sql("INSERT INTO identity_account (account_id, display_name) VALUES (:actorId, 'Audit test actor')")
                .param("actorId", actorId)
                .update();
    }

    private long accountCount(UUID actorId) {
        return jdbcClient.sql("SELECT count(*) FROM identity_account WHERE account_id = :actorId")
                .param("actorId", actorId)
                .query(Long.class)
                .single();
    }

    private long auditCount(UUID eventId) {
        return jdbcClient.sql("SELECT count(*) FROM audit_event WHERE event_id = :eventId")
                .param("eventId", eventId)
                .query(Long.class)
                .single();
    }
}

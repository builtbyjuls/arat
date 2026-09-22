package com.builtbyjuls.arat.messaging.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AppendOutboxEventCommandTest {

    private static final UUID AGGREGATE_ID = UUID.fromString("30000000-0000-4000-8000-000000000012");

    @Test
    void generatesAnEventIdWhenTheCallerDoesNotProvideOne() {
        var command = new AppendOutboxEventCommand(null, "ProviderRequestPublished:request:provider", envelope());

        assertThat(command.eventId()).isNotNull();
    }

    @Test
    void rejectsInvalidBoundedEnvelopeFields() {
        assertThatIllegalArgumentException().isThrownBy(() -> new AppendOutboxEventCommand(
                UUID.randomUUID(), " ", envelope()));
        assertThatIllegalArgumentException().isThrownBy(() -> new AppendOutboxEventCommand(
                UUID.randomUUID(), "x".repeat(257), envelope()));
        assertThatIllegalArgumentException().isThrownBy(() -> new OutboxEventEnvelope(
                " ", 1, OffsetDateTime.parse("2026-09-23T08:00:00Z"), "PublishedRequest", AGGREGATE_ID, 7, "trace", "{}"));
        assertThatIllegalArgumentException().isThrownBy(() -> new OutboxEventEnvelope(
                "x".repeat(121), 1, OffsetDateTime.parse("2026-09-23T08:00:00Z"), "PublishedRequest", AGGREGATE_ID, 7, "trace", "{}"));
        assertThatIllegalArgumentException().isThrownBy(() -> new OutboxEventEnvelope(
                "ProviderRequestPublished", 0, OffsetDateTime.parse("2026-09-23T08:00:00Z"), "PublishedRequest", AGGREGATE_ID, 7, "trace", "{}"));
        assertThatIllegalArgumentException().isThrownBy(() -> new OutboxEventEnvelope(
                "ProviderRequestPublished", 1, OffsetDateTime.parse("2026-09-23T08:00:00Z"), " ", AGGREGATE_ID, 7, "trace", "{}"));
        assertThatIllegalArgumentException().isThrownBy(() -> new OutboxEventEnvelope(
                "ProviderRequestPublished", 1, OffsetDateTime.parse("2026-09-23T08:00:00Z"), "x".repeat(121), AGGREGATE_ID, 7, "trace", "{}"));
        assertThatIllegalArgumentException().isThrownBy(() -> new OutboxEventEnvelope(
                "ProviderRequestPublished", 1, OffsetDateTime.parse("2026-09-23T08:00:00Z"), "PublishedRequest", AGGREGATE_ID, 0, "trace", "{}"));
        assertThatIllegalArgumentException().isThrownBy(() -> new OutboxEventEnvelope(
                "ProviderRequestPublished", 1, OffsetDateTime.parse("2026-09-23T08:00:00Z"), "PublishedRequest", AGGREGATE_ID, 7, " ", "{}"));
        assertThatIllegalArgumentException().isThrownBy(() -> new OutboxEventEnvelope(
                "ProviderRequestPublished", 1, OffsetDateTime.parse("2026-09-23T08:00:00Z"), "PublishedRequest", AGGREGATE_ID, 7, "x".repeat(121), "{}"));
    }

    private OutboxEventEnvelope envelope() {
        return new OutboxEventEnvelope(
                "ProviderRequestPublished",
                1,
                OffsetDateTime.parse("2026-09-23T08:00:00Z"),
                "PublishedRequest",
                AGGREGATE_ID,
                7,
                "trace-012",
                "{}");
    }
}

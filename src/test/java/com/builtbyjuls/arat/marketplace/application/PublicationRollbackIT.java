package com.builtbyjuls.arat.marketplace.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.doAnswer;

import com.builtbyjuls.arat.PostgreSqlIntegrationTest;
import com.builtbyjuls.arat.marketplace.api.ClosePublishedRequestCommand;
import com.builtbyjuls.arat.marketplace.api.PublishRequestCommand;
import com.builtbyjuls.arat.marketplace.domain.RequestRecipient;
import com.builtbyjuls.arat.marketplace.infrastructure.RequestRecipientRepository;
import com.builtbyjuls.arat.messaging.api.AppendOutboxEventCommand;
import com.builtbyjuls.arat.messaging.api.MessagingAccess;
import com.builtbyjuls.arat.planning.api.CancelPlanCommand;
import com.builtbyjuls.arat.planning.domain.PlanState;
import com.builtbyjuls.arat.planning.domain.PublishedRequest;
import com.builtbyjuls.arat.planning.domain.PublishedRequestState;
import com.builtbyjuls.arat.planning.infrastructure.PlanRepository;
import com.builtbyjuls.arat.planning.infrastructure.PublishedRequestRepository;
import com.builtbyjuls.arat.platform.audit.AuditEvent;
import com.builtbyjuls.arat.platform.audit.AuditEventWriter;
import com.builtbyjuls.arat.platform.idempotency.CompletedIdempotencyResponse;
import com.builtbyjuls.arat.platform.idempotency.IdempotencyRepository;
import com.builtbyjuls.arat.platform.idempotency.IdempotencyScope;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PublicationRollbackIT extends PostgreSqlIntegrationTest {

    @Autowired private JdbcClient jdbcClient;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private RequestPublicationService publicationService;
    @Autowired private RequestClosureService closureService;
    @Autowired private PlanCancellationService cancellationService;

    @MockitoSpyBean private PublishedRequestRepository requestRepository;
    @MockitoSpyBean private PlanRepository planRepository;
    @MockitoSpyBean private RequestRecipientRepository recipientRepository;
    @MockitoSpyBean private MessagingAccess messagingAccess;
    @MockitoSpyBean private AuditEventWriter auditEventWriter;
    @MockitoSpyBean private IdempotencyRepository idempotencyRepository;

    private TransactionTemplate freshRead;

    @BeforeEach
    void prepareFreshReadTransaction() {
        freshRead = new TransactionTemplate(transactionManager);
        freshRead.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        freshRead.setReadOnly(true);
    }

    @ParameterizedTest
    @EnumSource(FirstPublicationBoundary.class)
    void firstPublicationRollsBackAfterEveryNamedBoundary(FirstPublicationBoundary boundary) {
        var fixture = fixture(2);
        var before = durableState(fixture);
        injectFirstPublicationFailure(boundary);

        assertThatThrownBy(() -> publicationService.publish(publishCommand(
                        fixture, fixture.finalizationId(), 1, "first-" + key(boundary))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("forced first publication");

        assertThat(durableState(fixture)).isEqualTo(before);
    }

    @ParameterizedTest
    @EnumSource(ReplacementBoundary.class)
    void replacementRollsBackAfterSupersessionAndPartialNewWork(ReplacementBoundary boundary) {
        var fixture = fixture(2);
        var first = publicationService.publish(publishCommand(
                fixture, fixture.finalizationId(), 1, "replacement-baseline"));
        var replacementFinalizationId = insertFinalization(fixture, 2);
        var before = durableState(fixture);
        injectReplacementFailure(boundary);

        assertThatThrownBy(() -> publicationService.publish(publishCommand(
                        fixture, replacementFinalizationId, 2, "replacement-" + key(boundary))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("forced replacement");

        assertThat(durableState(fixture)).isEqualTo(before);
        assertPlan(fixture.planId(), "OPEN_FOR_OFFERS", first.request().requestId(), 2);
        assertRequest(first.request().requestId(), "OPEN", false);
    }

    @Test
    void closureRollsBackAfterTerminalEventWrites() {
        var fixture = fixture(2);
        var published = publicationService.publish(publishCommand(
                fixture, fixture.finalizationId(), 1, "closure-baseline"));
        var requestId = published.request().requestId();
        var before = durableState(fixture);
        failAfterAuditAppend("forced closure after terminal events");

        assertThatThrownBy(() -> closureService.close(new ClosePublishedRequestCommand(
                        fixture.organizerId(), requestId, 2, "closure-failure", fixture.correlationId())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("forced closure");

        assertThat(durableState(fixture)).isEqualTo(before);
        assertPlan(fixture.planId(), "OPEN_FOR_OFFERS", requestId, 2);
        assertRequest(requestId, "OPEN", false);
    }

    @Test
    void cancellationRollsBackAfterTerminalEventWrites() {
        var fixture = fixture(2);
        var published = publicationService.publish(publishCommand(
                fixture, fixture.finalizationId(), 1, "cancellation-baseline"));
        var requestId = published.request().requestId();
        var before = durableState(fixture);
        failAfterAuditAppend("forced cancellation after terminal events");

        assertThatThrownBy(() -> cancellationService.cancel(new CancelPlanCommand(
                        fixture.organizerId(), fixture.planId(), 2,
                        "cancellation-failure", fixture.correlationId())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("forced cancellation");

        assertThat(durableState(fixture)).isEqualTo(before);
        assertPlan(fixture.planId(), "OPEN_FOR_OFFERS", requestId, 2);
        assertRequest(requestId, "OPEN", false);
    }

    @Test
    void successfulPublicationReplacementAndClosureEmitOneEventPerRecipient() {
        var fixture = fixture(2);
        var first = publicationService.publish(publishCommand(
                fixture, fixture.finalizationId(), 1, "success-first"));
        var replacementFinalizationId = insertFinalization(fixture, 2);
        var second = publicationService.publish(publishCommand(
                fixture, replacementFinalizationId, 2, "success-replacement"));

        closureService.close(new ClosePublishedRequestCommand(
                fixture.organizerId(), second.request().requestId(), 3,
                "success-closure", fixture.correlationId()));

        assertOneEventPerRecipient(first.request().requestId(), "ProviderRequestPublished");
        assertOneEventPerRecipient(first.request().requestId(), "ProviderRequestSuperseded");
        assertOneEventPerRecipient(second.request().requestId(), "ProviderRequestPublished");
        assertOneEventPerRecipient(second.request().requestId(), "ProviderRequestClosed");
        assertPlan(fixture.planId(), "COLLABORATING", null, 4);
        assertRequest(first.request().requestId(), "SUPERSEDED", true);
        assertRequest(second.request().requestId(), "CLOSED", true);
    }

    @Test
    void successfulCancellationEmitsOneEventPerRecipient() {
        var fixture = fixture(2);
        var published = publicationService.publish(publishCommand(
                fixture, fixture.finalizationId(), 1, "success-cancellation-baseline"));

        cancellationService.cancel(new CancelPlanCommand(
                fixture.organizerId(), fixture.planId(), 2,
                "success-cancellation", fixture.correlationId()));

        assertOneEventPerRecipient(published.request().requestId(), "ProviderRequestPublished");
        assertOneEventPerRecipient(published.request().requestId(), "ProviderRequestCancelled");
        assertPlan(fixture.planId(), "CANCELLED", null, 3);
        assertRequest(published.request().requestId(), "CANCELLED", true);
    }

    private void injectFirstPublicationFailure(FirstPublicationBoundary boundary) {
        var exception = new IllegalStateException("forced first publication " + key(boundary));
        switch (boundary) {
            case REQUEST_INSERT -> doAnswer(invocation -> {
                invocation.callRealMethod();
                throw exception;
            }).when(target(requestRepository)).insert(any(PublishedRequest.class));
            case PLAN_TRANSITION -> doAnswer(invocation -> {
                invocation.callRealMethod();
                throw exception;
            }).when(target(planRepository)).transitionRequestPointerVersioned(
                    any(UUID.class), anyLong(), any(PlanState.class), nullable(UUID.class),
                    any(PlanState.class), nullable(UUID.class));
            case RECIPIENT_INSERT -> doAnswer(invocation -> {
                invocation.callRealMethod();
                throw exception;
            }).when(target(recipientRepository)).insert(any(RequestRecipient.class));
            case OUTBOX_APPEND -> doAnswer(invocation -> {
                invocation.callRealMethod();
                throw exception;
            }).when(target(messagingAccess)).append(any(AppendOutboxEventCommand.class));
            case AUDIT_APPEND -> doAnswer(invocation -> {
                invocation.callRealMethod();
                throw exception;
            }).when(target(auditEventWriter)).append(any(AuditEvent.class));
            case IDEMPOTENCY_COMPLETION -> doAnswer(invocation -> {
                invocation.callRealMethod();
                throw exception;
            }).when(target(idempotencyRepository)).complete(
                    any(IdempotencyScope.class), any(CompletedIdempotencyResponse.class));
        }
    }

    private void injectReplacementFailure(ReplacementBoundary boundary) {
        var exception = new IllegalStateException("forced replacement " + key(boundary));
        switch (boundary) {
            case OLD_REQUEST_SUPERSESSION -> doAnswer(invocation -> {
                invocation.callRealMethod();
                throw exception;
            }).when(target(requestRepository)).transitionOpen(
                    any(UUID.class), any(UUID.class), any(PublishedRequestState.class), any(OffsetDateTime.class));
            case PARTIAL_NEW_REQUEST_WORK -> doAnswer(invocation -> {
                invocation.callRealMethod();
                throw exception;
            }).when(target(recipientRepository)).insert(any(RequestRecipient.class));
        }
    }

    private void failAfterAuditAppend(String message) {
        doAnswer(invocation -> {
            invocation.callRealMethod();
            throw new IllegalStateException(message);
        }).when(target(auditEventWriter)).append(any(AuditEvent.class));
    }

    private Fixture fixture(int providerCount) {
        var organizerId = UUID.randomUUID();
        var groupId = UUID.randomUUID();
        var planId = UUID.randomUUID();
        var windowId = UUID.randomUUID();
        var areaCode = "AREA-" + planId.toString().substring(0, 8);
        var correlationId = "publication-rollback-" + planId;
        var startAt = jdbcClient.sql("SELECT clock_timestamp() + INTERVAL '3 days'")
                .query(OffsetDateTime.class)
                .single();

        jdbcClient.sql("""
                        INSERT INTO identity_account (account_id, display_name)
                        VALUES (:organizerId, 'Rollback organizer')
                        """)
                .param("organizerId", organizerId)
                .update();
        jdbcClient.sql("""
                        INSERT INTO group_account (
                            group_id, name, description, status, created_by_account_id
                        ) VALUES (:groupId, 'Rollback group', '', 'ACTIVE', :organizerId)
                        """)
                .param("groupId", groupId)
                .param("organizerId", organizerId)
                .update();
        jdbcClient.sql("""
                        INSERT INTO group_membership (group_id, account_id, role, status, joined_at)
                        VALUES (:groupId, :organizerId, 'ORGANIZER', 'ACTIVE', statement_timestamp())
                        """)
                .param("groupId", groupId)
                .param("organizerId", organizerId)
                .update();
        jdbcClient.sql("""
                        INSERT INTO planning_plan (
                            plan_id, group_id, title, state, created_by_account_id, version
                        ) VALUES (
                            :planId, :groupId, 'Rollback plan', 'COLLABORATING', :organizerId, 1
                        )
                        """)
                .param("planId", planId)
                .param("groupId", groupId)
                .param("organizerId", organizerId)
                .update();
        jdbcClient.sql("""
                        INSERT INTO planning_requirement_draft (
                            plan_id, category, time_zone, area_code, radius_km,
                            minimum_headcount, maximum_headcount, budget_currency,
                            budget_minimum_minor_units, budget_maximum_minor_units,
                            provider_safe_notes, category_attributes
                        ) VALUES (
                            :planId, 'COURT', 'Asia/Manila', :areaCode, 5,
                            4, 10, 'PHP', 100000, 250000,
                            'Indoor court preferred.', '{"courtCount":2}'::jsonb
                        )
                        """)
                .param("planId", planId)
                .param("areaCode", areaCode)
                .update();
        jdbcClient.sql("""
                        INSERT INTO planning_candidate_window (
                            candidate_window_id, plan_id, sort_order, starts_at, ends_at
                        ) VALUES (:windowId, :planId, 1, :startAt, :endAt)
                        """)
                .param("windowId", windowId)
                .param("planId", planId)
                .param("startAt", startAt)
                .param("endAt", startAt.plusHours(2))
                .update();
        jdbcClient.sql("""
                        INSERT INTO planning_requirement_must_have (plan_id, must_have, sort_order)
                        VALUES (:planId, 'parking', 1)
                        """)
                .param("planId", planId)
                .update();

        var providerIds = new ArrayList<UUID>();
        for (var index = 0; index < providerCount; index++) {
            var providerId = UUID.randomUUID();
            providerIds.add(providerId);
            jdbcClient.sql("""
                            INSERT INTO provider_organization (
                                provider_id, display_name, status, verification_status,
                                version, eligibility_version
                            ) VALUES (
                                :providerId, :displayName, 'ACTIVE', 'VERIFIED', 2, 2
                            )
                            """)
                    .param("providerId", providerId)
                    .param("displayName", "Rollback provider " + index)
                    .update();
            jdbcClient.sql("""
                            INSERT INTO provider_supported_category (provider_id, category)
                            VALUES (:providerId, 'COURT')
                            """)
                    .param("providerId", providerId)
                    .update();
            jdbcClient.sql("""
                            INSERT INTO provider_service_area (provider_id, area_code)
                            VALUES (:providerId, :areaCode)
                            """)
                    .param("providerId", providerId)
                    .param("areaCode", areaCode)
                    .update();
        }
        providerIds.sort(Comparator.comparing(UUID::toString));
        var fixture = new Fixture(
                organizerId, groupId, planId, windowId, null, areaCode,
                startAt, correlationId, List.copyOf(providerIds));
        return fixture.withFinalizationId(insertFinalization(fixture, 1));
    }

    private UUID insertFinalization(Fixture fixture, long basisPlanVersion) {
        var finalizationId = UUID.randomUUID();
        jdbcClient.sql("""
                        INSERT INTO planning_requirement_finalization (
                            finalization_id, plan_id, basis_plan_version,
                            selected_candidate_window_id, selected_starts_at, selected_ends_at,
                            offer_deadline, category, time_zone, area_code, radius_km,
                            minimum_headcount, maximum_headcount, budget_currency,
                            budget_minimum_minor_units, budget_maximum_minor_units, must_haves,
                            provider_safe_notes, category_attributes, current_preference_count,
                            stale_preference_count, warnings, finalized_by_account_id, created_at
                        )
                        SELECT
                            :finalizationId, p.plan_id, :basisPlanVersion,
                            w.candidate_window_id, w.starts_at, w.ends_at,
                            w.starts_at - INTERVAL '1 hour', d.category, d.time_zone,
                            d.area_code, d.radius_km, d.minimum_headcount, d.maximum_headcount,
                            d.budget_currency, d.budget_minimum_minor_units,
                            d.budget_maximum_minor_units,
                            ARRAY['parking']::varchar[], d.provider_safe_notes,
                            d.category_attributes, 0, 0,
                            ARRAY['NO_CURRENT_PREFERENCE_INPUT']::varchar[],
                            :organizerId, statement_timestamp()
                        FROM planning_plan p
                        JOIN planning_requirement_draft d ON d.plan_id = p.plan_id
                        JOIN planning_candidate_window w ON w.plan_id = p.plan_id
                        WHERE p.plan_id = :planId
                          AND w.candidate_window_id = :windowId
                        """)
                .param("finalizationId", finalizationId)
                .param("basisPlanVersion", basisPlanVersion)
                .param("organizerId", fixture.organizerId())
                .param("planId", fixture.planId())
                .param("windowId", fixture.windowId())
                .update();
        return finalizationId;
    }

    private PublishRequestCommand publishCommand(
            Fixture fixture, UUID finalizationId, long expectedPlanVersion, String idempotencyKey) {
        return new PublishRequestCommand(
                fixture.organizerId(), fixture.planId(), finalizationId,
                expectedPlanVersion, idempotencyKey, fixture.correlationId());
    }

    private DurableState durableState(Fixture fixture) {
        return freshRead.execute(status -> new DurableState(
                rows("""
                                SELECT to_jsonb(p)::text
                                FROM planning_plan p
                                WHERE p.plan_id = :planId
                                ORDER BY p.plan_id
                                """, Map.of("planId", fixture.planId())),
                rows("""
                                SELECT to_jsonb(r)::text
                                FROM planning_published_request r
                                WHERE r.plan_id = :planId
                                ORDER BY r.request_version
                                """, Map.of("planId", fixture.planId())),
                rows("""
                                SELECT to_jsonb(rr)::text
                                FROM marketplace_request_recipient rr
                                JOIN planning_published_request r
                                  ON r.request_id = rr.published_request_id
                                WHERE r.plan_id = :planId
                                ORDER BY rr.published_request_id, rr.provider_id
                                """, Map.of("planId", fixture.planId())),
                rows("""
                                SELECT to_jsonb(o)::text
                                FROM messaging_outbox o
                                WHERE o.trace_id = :correlationId
                                ORDER BY o.business_key
                                """, Map.of("correlationId", fixture.correlationId())),
                rows("""
                                SELECT to_jsonb(a)::text
                                FROM audit_event a
                                WHERE a.plan_id = :planId
                                ORDER BY a.event_id
                                """, Map.of("planId", fixture.planId())),
                rows("""
                                SELECT to_jsonb(i)::text
                                FROM idempotency_record i
                                WHERE i.actor_id = :actorId
                                ORDER BY i.operation, i.idempotency_key
                                """, Map.of("actorId", fixture.organizerId()))));
    }

    private List<String> rows(String sql, Map<String, Object> parameters) {
        var statement = jdbcClient.sql(sql);
        for (var parameter : parameters.entrySet()) {
            statement = statement.param(parameter.getKey(), parameter.getValue());
        }
        return statement.query(String.class).list();
    }

    private void assertOneEventPerRecipient(UUID requestId, String eventType) {
        var providerIds = freshRead.execute(status -> jdbcClient.sql("""
                        SELECT provider_id
                        FROM marketplace_request_recipient
                        WHERE published_request_id = :requestId
                        ORDER BY provider_id::text
                        """)
                .param("requestId", requestId)
                .query(UUID.class)
                .list());
        var actualKeys = freshRead.execute(status -> jdbcClient.sql("""
                        SELECT business_key
                        FROM messaging_outbox
                        WHERE aggregate_id = :requestId
                          AND event_type = :eventType
                        ORDER BY business_key
                        """)
                .param("requestId", requestId)
                .param("eventType", eventType)
                .query(String.class)
                .list());
        assertThat(actualKeys).containsExactlyElementsOf(providerIds.stream()
                .map(providerId -> eventType + ":" + requestId + ":" + providerId)
                .sorted()
                .toList());
    }

    private void assertPlan(UUID planId, String state, UUID currentRequestId, long version) {
        var plan = freshRead.execute(status -> jdbcClient.sql("""
                        SELECT state, current_request_id, version
                        FROM planning_plan
                        WHERE plan_id = :planId
                        """)
                .param("planId", planId)
                .query((resultSet, rowNum) -> new PlanRow(
                        resultSet.getString("state"),
                        resultSet.getObject("current_request_id", UUID.class),
                        resultSet.getLong("version")))
                .single());
        assertThat(plan).isEqualTo(new PlanRow(state, currentRequestId, version));
    }

    private void assertRequest(UUID requestId, String state, boolean terminal) {
        var request = freshRead.execute(status -> jdbcClient.sql("""
                        SELECT state, closed_at IS NOT NULL AS terminal
                        FROM planning_published_request
                        WHERE request_id = :requestId
                        """)
                .param("requestId", requestId)
                .query((resultSet, rowNum) -> new RequestRow(
                        resultSet.getString("state"), resultSet.getBoolean("terminal")))
                .single());
        assertThat(request).isEqualTo(new RequestRow(state, terminal));
    }

    private String key(Enum<?> value) {
        return value.name().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    private <T> T target(T bean) {
        return AopTestUtils.getTargetObject(bean);
    }

    private record Fixture(
            UUID organizerId,
            UUID groupId,
            UUID planId,
            UUID windowId,
            UUID finalizationId,
            String areaCode,
            OffsetDateTime startAt,
            String correlationId,
            List<UUID> providerIds) {

        private Fixture withFinalizationId(UUID value) {
            return new Fixture(
                    organizerId, groupId, planId, windowId, value, areaCode,
                    startAt, correlationId, providerIds);
        }
    }

    private record DurableState(
            List<String> plans,
            List<String> requests,
            List<String> recipients,
            List<String> outbox,
            List<String> audit,
            List<String> idempotency) {
    }

    private record PlanRow(String state, UUID currentRequestId, long version) {
    }

    private record RequestRow(String state, boolean terminal) {
    }

    private enum FirstPublicationBoundary {
        REQUEST_INSERT,
        PLAN_TRANSITION,
        RECIPIENT_INSERT,
        OUTBOX_APPEND,
        AUDIT_APPEND,
        IDEMPOTENCY_COMPLETION
    }

    private enum ReplacementBoundary {
        OLD_REQUEST_SUPERSESSION,
        PARTIAL_NEW_REQUEST_WORK
    }
}

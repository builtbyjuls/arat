package com.builtbyjuls.arat.marketplace.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.builtbyjuls.arat.PostgreSqlIntegrationTest;
import com.builtbyjuls.arat.groups.api.RemoveGroupMemberCommand;
import com.builtbyjuls.arat.groups.api.TransferOrganizerCommand;
import com.builtbyjuls.arat.groups.application.MembershipExitService;
import com.builtbyjuls.arat.groups.application.OrganizerTransferService;
import com.builtbyjuls.arat.marketplace.api.ClosePublishedRequestCommand;
import com.builtbyjuls.arat.marketplace.api.PublishRequestCommand;
import com.builtbyjuls.arat.marketplace.api.RequestClosureException;
import com.builtbyjuls.arat.marketplace.api.RequestClosureResponse;
import com.builtbyjuls.arat.marketplace.api.RequestPublicationException;
import com.builtbyjuls.arat.marketplace.api.RequestPublicationResponse;
import com.builtbyjuls.arat.planning.api.CancelPlanCommand;
import com.builtbyjuls.arat.planning.api.CreatePlanRequest;
import com.builtbyjuls.arat.planning.api.PlanCancellationException;
import com.builtbyjuls.arat.planning.api.PlanRepresentation;
import com.builtbyjuls.arat.planning.api.ReplaceRequirementsCommand;
import com.builtbyjuls.arat.planning.api.RequirementReplacementException;
import com.builtbyjuls.arat.planning.api.RequirementReplacementRequest;
import com.builtbyjuls.arat.planning.api.RequirementRepresentation;
import com.builtbyjuls.arat.planning.application.RequirementReplacementService;
import com.builtbyjuls.arat.planning.domain.ActivityCategory;
import com.builtbyjuls.arat.testing.ConcurrentDatabaseWorkers;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;

@SpringBootTest
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PublicationRaceOutcomesIT extends PostgreSqlIntegrationTest {

    private static final String PUBLICATION_OPERATION = RequestPublicationService.PUBLISH_REQUEST_OPERATION;
    private static final String TRANSFER_OPERATION = OrganizerTransferService.TRANSFER_ORGANIZER_OPERATION;
    private static final String REMOVE_OPERATION = MembershipExitService.REMOVE_OPERATION;
    private static final String CLOSE_OPERATION = RequestClosureService.CLOSE_REQUEST_OPERATION;
    private static final String CANCEL_OPERATION = PlanCancellationService.CANCEL_PLAN_OPERATION;

    @Autowired private JdbcClient jdbcClient;
    @Autowired private DataSource dataSource;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private RequestPublicationService publicationService;
    @Autowired private OrganizerTransferService organizerTransferService;
    @Autowired private MembershipExitService membershipExitService;
    @Autowired private RequirementReplacementService requirementReplacementService;
    @Autowired private RequestClosureService closureService;
    @Autowired private PlanCancellationService cancellationService;

    @Test
    void twoDifferentPublicationKeysWithOnePlanEtagCommitExactlyOneVersion() throws Exception {
        var fixture = fixture("MEMBER");

        var outcomes = race(
                fixture,
                () -> publicationService.publish(publishCommand(
                        fixture, fixture.finalizationId(), 1, "different-first")),
                () -> publicationService.publish(publishCommand(
                        fixture, fixture.finalizationId(), 1, "different-second")));

        var published = success(outcomes.getFirst(), RequestPublicationResponse.class);
        assertPublicationFailure(outcomes.getLast(), RequestPublicationException.Reason.PRECONDITION_FAILED);
        var requestId = published.request().requestId();

        assertPlan(fixture, "Race plan", "OPEN_FOR_OFFERS", requestId, 2);
        assertRequests(fixture, new RequestRow(requestId, 1, "OPEN", false, fixture.areaCode(), "Original notes."));
        assertFinalizations(fixture, new FinalizationRef(fixture.finalizationId(), 1));
        assertRecipients(fixture, requestId);
        assertOutbox(fixture, new EventRef("ProviderRequestPublished", requestId));
        assertAudit(fixture, new AuditRef("published_request.published", requestId, fixture.organizerId()));
        assertIdempotency(fixture, new IdempotencyRef(
                fixture.organizerId(), PUBLICATION_OPERATION, "different-first", requestId));
        assertMemberships(fixture, "ORGANIZER", "ACTIVE", "MEMBER", "ACTIVE");
    }

    @Test
    void concurrentExactReplayReturnsOneCompletePublication() throws Exception {
        var fixture = fixture("MEMBER");
        var command = publishCommand(fixture, fixture.finalizationId(), 1, "exact-replay");

        var outcomes = race(
                fixture,
                () -> publicationService.publish(command),
                () -> publicationService.publish(command));

        var first = success(outcomes.getFirst(), RequestPublicationResponse.class);
        var replay = success(outcomes.getLast(), RequestPublicationResponse.class);
        assertThat(replay).isEqualTo(first);
        var requestId = first.request().requestId();

        assertPlan(fixture, "Race plan", "OPEN_FOR_OFFERS", requestId, 2);
        assertRequests(fixture, new RequestRow(requestId, 1, "OPEN", false, fixture.areaCode(), "Original notes."));
        assertFinalizations(fixture, new FinalizationRef(fixture.finalizationId(), 1));
        assertRecipients(fixture, requestId);
        assertOutbox(fixture, new EventRef("ProviderRequestPublished", requestId));
        assertAudit(fixture, new AuditRef("published_request.published", requestId, fixture.organizerId()));
        assertIdempotency(fixture, new IdempotencyRef(
                fixture.organizerId(), PUBLICATION_OPERATION, "exact-replay", requestId));
    }

    @Test
    void organizerTransferAndPublicationSupportBothGroupLockOrders() throws Exception {
        var transferFirst = fixture("MEMBER");
        var transferFirstOutcomes = race(
                transferFirst,
                () -> organizerTransferService.transfer(transferCommand(transferFirst, "transfer-first")),
                () -> publicationService.publish(publishCommand(
                        transferFirst, transferFirst.finalizationId(), 1, "publish-after-transfer")));

        assertThat(transferFirstOutcomes.getFirst().succeeded()).isTrue();
        assertPublicationFailure(
                transferFirstOutcomes.getLast(), RequestPublicationException.Reason.FORBIDDEN_ROLE);
        assertPlan(transferFirst, "Race plan", "COLLABORATING", null, 1);
        assertRequests(transferFirst);
        assertFinalizations(transferFirst, new FinalizationRef(transferFirst.finalizationId(), 1));
        assertRecipients(transferFirst);
        assertOutbox(transferFirst);
        assertAudit(transferFirst, new AuditRef(
                "group.organizer.transferred", transferFirst.secondOrganizerId(), transferFirst.organizerId()));
        assertIdempotency(transferFirst, new IdempotencyRef(
                transferFirst.organizerId(), TRANSFER_OPERATION, "transfer-first", transferFirst.groupId()));
        assertMemberships(transferFirst, "MEMBER", "ACTIVE", "ORGANIZER", "ACTIVE");

        var publicationFirst = fixture("MEMBER");
        var publicationFirstOutcomes = race(
                publicationFirst,
                () -> publicationService.publish(publishCommand(
                        publicationFirst, publicationFirst.finalizationId(), 1, "publish-first")),
                () -> organizerTransferService.transfer(transferCommand(
                        publicationFirst, "transfer-after-publish")));

        var published = success(publicationFirstOutcomes.getFirst(), RequestPublicationResponse.class);
        assertThat(publicationFirstOutcomes.getLast().succeeded()).isTrue();
        var requestId = published.request().requestId();
        assertPlan(publicationFirst, "Race plan", "OPEN_FOR_OFFERS", requestId, 2);
        assertRequests(publicationFirst, new RequestRow(
                requestId, 1, "OPEN", false, publicationFirst.areaCode(), "Original notes."));
        assertFinalizations(publicationFirst, new FinalizationRef(publicationFirst.finalizationId(), 1));
        assertRecipients(publicationFirst, requestId);
        assertOutbox(publicationFirst, new EventRef("ProviderRequestPublished", requestId));
        assertAudit(
                publicationFirst,
                new AuditRef("published_request.published", requestId, publicationFirst.organizerId()),
                new AuditRef("group.organizer.transferred", publicationFirst.secondOrganizerId(),
                        publicationFirst.organizerId()));
        assertIdempotency(
                publicationFirst,
                new IdempotencyRef(publicationFirst.organizerId(), PUBLICATION_OPERATION, "publish-first", requestId),
                new IdempotencyRef(publicationFirst.organizerId(), TRANSFER_OPERATION,
                        "transfer-after-publish", publicationFirst.groupId()));
        assertMemberships(publicationFirst, "MEMBER", "ACTIVE", "ORGANIZER", "ACTIVE");
    }

    @Test
    void organizerRemovalAndPublicationSupportBothGroupLockOrders() throws Exception {
        var removalFirst = fixture("ORGANIZER");
        var removalFirstOutcomes = race(
                removalFirst,
                () -> membershipExitService.remove(removalCommand(removalFirst, "remove-first")),
                () -> publicationService.publish(publishCommand(
                        removalFirst, removalFirst.finalizationId(), 1, "publish-after-removal")));

        assertThat(removalFirstOutcomes.getFirst().succeeded()).isTrue();
        assertPublicationFailure(
                removalFirstOutcomes.getLast(), RequestPublicationException.Reason.PRIVATE_RESOURCE_NOT_FOUND);
        assertPlan(removalFirst, "Race plan", "COLLABORATING", null, 1);
        assertRequests(removalFirst);
        assertFinalizations(removalFirst, new FinalizationRef(removalFirst.finalizationId(), 1));
        assertRecipients(removalFirst);
        assertOutbox(removalFirst);
        assertAudit(removalFirst, new AuditRef(
                "group.membership.removed", removalFirst.organizerId(), removalFirst.secondOrganizerId()));
        assertIdempotency(removalFirst, new IdempotencyRef(
                removalFirst.secondOrganizerId(), REMOVE_OPERATION, "remove-first", removalFirst.organizerId()));
        assertMemberships(removalFirst, "ORGANIZER", "REMOVED", "ORGANIZER", "ACTIVE");

        var publicationFirst = fixture("ORGANIZER");
        var publicationFirstOutcomes = race(
                publicationFirst,
                () -> publicationService.publish(publishCommand(
                        publicationFirst, publicationFirst.finalizationId(), 1, "publish-before-removal")),
                () -> membershipExitService.remove(removalCommand(
                        publicationFirst, "remove-after-publish")));

        var published = success(publicationFirstOutcomes.getFirst(), RequestPublicationResponse.class);
        assertThat(publicationFirstOutcomes.getLast().succeeded()).isTrue();
        var requestId = published.request().requestId();
        assertPlan(publicationFirst, "Race plan", "OPEN_FOR_OFFERS", requestId, 2);
        assertRequests(publicationFirst, new RequestRow(
                requestId, 1, "OPEN", false, publicationFirst.areaCode(), "Original notes."));
        assertFinalizations(publicationFirst, new FinalizationRef(publicationFirst.finalizationId(), 1));
        assertRecipients(publicationFirst, requestId);
        assertOutbox(publicationFirst, new EventRef("ProviderRequestPublished", requestId));
        assertAudit(
                publicationFirst,
                new AuditRef("published_request.published", requestId, publicationFirst.organizerId()),
                new AuditRef("group.membership.removed", publicationFirst.organizerId(),
                        publicationFirst.secondOrganizerId()));
        assertIdempotency(
                publicationFirst,
                new IdempotencyRef(publicationFirst.organizerId(), PUBLICATION_OPERATION,
                        "publish-before-removal", requestId),
                new IdempotencyRef(publicationFirst.secondOrganizerId(), REMOVE_OPERATION,
                        "remove-after-publish", publicationFirst.organizerId()));
        assertMemberships(publicationFirst, "ORGANIZER", "REMOVED", "ORGANIZER", "ACTIVE");
    }

    @Test
    void draftReplacementAndPublicationSupportBothPlanLockOrders() throws Exception {
        var replacementFirst = fixture("MEMBER");
        var replacementFirstOutcomes = race(
                replacementFirst,
                () -> requirementReplacementService.replace(replacementCommand(replacementFirst)),
                () -> publicationService.publish(publishCommand(
                        replacementFirst, replacementFirst.finalizationId(), 1, "publish-after-edit")));

        success(replacementFirstOutcomes.getFirst(), RequirementRepresentation.class);
        assertPublicationFailure(
                replacementFirstOutcomes.getLast(), RequestPublicationException.Reason.PRECONDITION_FAILED);
        assertPlan(replacementFirst, "Replacement draft", "COLLABORATING", null, 2);
        assertReplacementDraft(replacementFirst);
        assertRequests(replacementFirst);
        assertFinalizations(replacementFirst, new FinalizationRef(replacementFirst.finalizationId(), 1));
        assertRecipients(replacementFirst);
        assertOutbox(replacementFirst);
        assertAudit(replacementFirst, new AuditRef(
                "plan.requirements.replaced", replacementFirst.planId(), replacementFirst.organizerId()));
        assertIdempotency(replacementFirst);

        var publicationFirst = fixture("MEMBER");
        var publicationFirstOutcomes = race(
                publicationFirst,
                () -> publicationService.publish(publishCommand(
                        publicationFirst, publicationFirst.finalizationId(), 1, "publish-before-edit")),
                () -> requirementReplacementService.replace(replacementCommand(publicationFirst)));

        var published = success(publicationFirstOutcomes.getFirst(), RequestPublicationResponse.class);
        assertRequirementFailure(
                publicationFirstOutcomes.getLast(), RequirementReplacementException.Reason.PRECONDITION_FAILED);
        var requestId = published.request().requestId();
        assertPlan(publicationFirst, "Race plan", "OPEN_FOR_OFFERS", requestId, 2);
        assertOriginalDraft(publicationFirst);
        assertRequests(publicationFirst, new RequestRow(
                requestId, 1, "OPEN", false, publicationFirst.areaCode(), "Original notes."));
        assertFinalizations(publicationFirst, new FinalizationRef(publicationFirst.finalizationId(), 1));
        assertRecipients(publicationFirst, requestId);
        assertOutbox(publicationFirst, new EventRef("ProviderRequestPublished", requestId));
        assertAudit(publicationFirst, new AuditRef(
                "published_request.published", requestId, publicationFirst.organizerId()));
        assertIdempotency(publicationFirst, new IdempotencyRef(
                publicationFirst.organizerId(), PUBLICATION_OPERATION, "publish-before-edit", requestId));
    }

    @Test
    void republishAndClosureSupportBothPlanAndRequestLockOrders() throws Exception {
        var republishFirst = publishedFixture();
        var previousRequestId = currentRequestId(republishFirst);
        var nextFinalizationId = insertFinalization(republishFirst, 2);
        var republishFirstOutcomes = race(
                republishFirst,
                () -> publicationService.publish(publishCommand(
                        republishFirst, nextFinalizationId, 2, "republish-before-close")),
                () -> closureService.close(closeCommand(
                        republishFirst, previousRequestId, "close-after-republish")));

        var republished = success(republishFirstOutcomes.getFirst(), RequestPublicationResponse.class);
        assertClosureFailure(republishFirstOutcomes.getLast(), RequestClosureException.Reason.PRECONDITION_FAILED);
        var currentRequestId = republished.request().requestId();
        assertPlan(republishFirst, "Race plan", "OPEN_FOR_OFFERS", currentRequestId, 3);
        assertRequests(
                republishFirst,
                new RequestRow(previousRequestId, 1, "SUPERSEDED", true,
                        republishFirst.areaCode(), "Original notes."),
                new RequestRow(currentRequestId, 2, "OPEN", false,
                        republishFirst.areaCode(), "Original notes."));
        assertFinalizations(
                republishFirst,
                new FinalizationRef(republishFirst.finalizationId(), 1),
                new FinalizationRef(nextFinalizationId, 2));
        assertRecipients(republishFirst, previousRequestId, currentRequestId);
        assertOutbox(
                republishFirst,
                new EventRef("ProviderRequestPublished", previousRequestId),
                new EventRef("ProviderRequestSuperseded", previousRequestId),
                new EventRef("ProviderRequestPublished", currentRequestId));
        assertAudit(
                republishFirst,
                new AuditRef("published_request.published", previousRequestId, republishFirst.organizerId()),
                new AuditRef("published_request.published", currentRequestId, republishFirst.organizerId()));
        assertIdempotency(
                republishFirst,
                new IdempotencyRef(republishFirst.organizerId(), PUBLICATION_OPERATION,
                        "baseline-publication", previousRequestId),
                new IdempotencyRef(republishFirst.organizerId(), PUBLICATION_OPERATION,
                        "republish-before-close", currentRequestId));

        var closureFirst = publishedFixture();
        var closedRequestId = currentRequestId(closureFirst);
        var closureFinalizationId = insertFinalization(closureFirst, 2);
        var closureFirstOutcomes = race(
                closureFirst,
                () -> closureService.close(closeCommand(
                        closureFirst, closedRequestId, "close-before-republish")),
                () -> publicationService.publish(publishCommand(
                        closureFirst, closureFinalizationId, 2, "republish-after-close")));

        success(closureFirstOutcomes.getFirst(), RequestClosureResponse.class);
        assertPublicationFailure(
                closureFirstOutcomes.getLast(), RequestPublicationException.Reason.PRECONDITION_FAILED);
        assertPlan(closureFirst, "Race plan", "COLLABORATING", null, 3);
        assertRequests(closureFirst, new RequestRow(
                closedRequestId, 1, "CLOSED", true, closureFirst.areaCode(), "Original notes."));
        assertFinalizations(
                closureFirst,
                new FinalizationRef(closureFirst.finalizationId(), 1),
                new FinalizationRef(closureFinalizationId, 2));
        assertRecipients(closureFirst, closedRequestId);
        assertOutbox(
                closureFirst,
                new EventRef("ProviderRequestPublished", closedRequestId),
                new EventRef("ProviderRequestClosed", closedRequestId));
        assertAudit(
                closureFirst,
                new AuditRef("published_request.published", closedRequestId, closureFirst.organizerId()),
                new AuditRef("published_request.closed", closedRequestId, closureFirst.organizerId()));
        assertIdempotency(
                closureFirst,
                new IdempotencyRef(closureFirst.organizerId(), PUBLICATION_OPERATION,
                        "baseline-publication", closedRequestId),
                new IdempotencyRef(closureFirst.organizerId(), CLOSE_OPERATION,
                        "close-before-republish", closedRequestId));
    }

    @Test
    void republishAndCancellationSupportBothPlanAndRequestLockOrders() throws Exception {
        var republishFirst = publishedFixture();
        var previousRequestId = currentRequestId(republishFirst);
        var nextFinalizationId = insertFinalization(republishFirst, 2);
        var republishFirstOutcomes = race(
                republishFirst,
                () -> publicationService.publish(publishCommand(
                        republishFirst, nextFinalizationId, 2, "republish-before-cancel")),
                () -> cancellationService.cancel(cancelCommand(
                        republishFirst, "cancel-after-republish")));

        var republished = success(republishFirstOutcomes.getFirst(), RequestPublicationResponse.class);
        assertCancellationFailure(
                republishFirstOutcomes.getLast(), PlanCancellationException.Reason.PRECONDITION_FAILED);
        var currentRequestId = republished.request().requestId();
        assertPlan(republishFirst, "Race plan", "OPEN_FOR_OFFERS", currentRequestId, 3);
        assertRequests(
                republishFirst,
                new RequestRow(previousRequestId, 1, "SUPERSEDED", true,
                        republishFirst.areaCode(), "Original notes."),
                new RequestRow(currentRequestId, 2, "OPEN", false,
                        republishFirst.areaCode(), "Original notes."));
        assertFinalizations(
                republishFirst,
                new FinalizationRef(republishFirst.finalizationId(), 1),
                new FinalizationRef(nextFinalizationId, 2));
        assertRecipients(republishFirst, previousRequestId, currentRequestId);
        assertOutbox(
                republishFirst,
                new EventRef("ProviderRequestPublished", previousRequestId),
                new EventRef("ProviderRequestSuperseded", previousRequestId),
                new EventRef("ProviderRequestPublished", currentRequestId));
        assertAudit(
                republishFirst,
                new AuditRef("published_request.published", previousRequestId, republishFirst.organizerId()),
                new AuditRef("published_request.published", currentRequestId, republishFirst.organizerId()));
        assertIdempotency(
                republishFirst,
                new IdempotencyRef(republishFirst.organizerId(), PUBLICATION_OPERATION,
                        "baseline-publication", previousRequestId),
                new IdempotencyRef(republishFirst.organizerId(), PUBLICATION_OPERATION,
                        "republish-before-cancel", currentRequestId));

        var cancellationFirst = publishedFixture();
        var cancelledRequestId = currentRequestId(cancellationFirst);
        var cancellationFinalizationId = insertFinalization(cancellationFirst, 2);
        var cancellationFirstOutcomes = race(
                cancellationFirst,
                () -> cancellationService.cancel(cancelCommand(
                        cancellationFirst, "cancel-before-republish")),
                () -> publicationService.publish(publishCommand(
                        cancellationFirst, cancellationFinalizationId, 2, "republish-after-cancel")));

        success(cancellationFirstOutcomes.getFirst(), PlanRepresentation.class);
        assertPublicationFailure(
                cancellationFirstOutcomes.getLast(), RequestPublicationException.Reason.PRECONDITION_FAILED);
        assertPlan(cancellationFirst, "Race plan", "CANCELLED", null, 3);
        assertRequests(cancellationFirst, new RequestRow(
                cancelledRequestId, 1, "CANCELLED", true,
                cancellationFirst.areaCode(), "Original notes."));
        assertFinalizations(
                cancellationFirst,
                new FinalizationRef(cancellationFirst.finalizationId(), 1),
                new FinalizationRef(cancellationFinalizationId, 2));
        assertRecipients(cancellationFirst, cancelledRequestId);
        assertOutbox(
                cancellationFirst,
                new EventRef("ProviderRequestPublished", cancelledRequestId),
                new EventRef("ProviderRequestCancelled", cancelledRequestId));
        assertAudit(
                cancellationFirst,
                new AuditRef("published_request.published", cancelledRequestId,
                        cancellationFirst.organizerId()),
                new AuditRef("plan.cancelled", cancellationFirst.planId(),
                        cancellationFirst.organizerId()));
        assertIdempotency(
                cancellationFirst,
                new IdempotencyRef(cancellationFirst.organizerId(), PUBLICATION_OPERATION,
                        "baseline-publication", cancelledRequestId),
                new IdempotencyRef(cancellationFirst.organizerId(), CANCEL_OPERATION,
                        "cancel-before-republish", cancellationFirst.planId()));
    }

    private List<ConcurrentDatabaseWorkers.Outcome<Object>> race(
            Fixture fixture, Supplier<Object> first, Supplier<Object> second) throws Exception {
        return ConcurrentDatabaseWorkers.runOrdered(
                dataSource,
                transactionManager,
                "SELECT group_id FROM group_account WHERE group_id = ? FOR UPDATE",
                fixture.groupId(),
                first,
                second);
    }

    private Fixture publishedFixture() {
        var fixture = fixture("MEMBER");
        publicationService.publish(publishCommand(
                fixture, fixture.finalizationId(), 1, "baseline-publication"));
        return fixture;
    }

    private Fixture fixture(String secondOrganizerRole) {
        var organizerId = UUID.randomUUID();
        var secondOrganizerId = UUID.randomUUID();
        var groupId = UUID.randomUUID();
        var planId = UUID.randomUUID();
        var windowId = UUID.randomUUID();
        var areaCode = "AREA-" + planId.toString().substring(0, 8);
        var correlationId = "publication-race-" + planId;
        var startAt = jdbcClient.sql("SELECT clock_timestamp() + INTERVAL '3 days'")
                .query(OffsetDateTime.class)
                .single();

        jdbcClient.sql("""
                        INSERT INTO identity_account (account_id, display_name)
                        VALUES (:organizerId, 'Race organizer'),
                               (:secondOrganizerId, 'Race second organizer')
                        """)
                .param("organizerId", organizerId)
                .param("secondOrganizerId", secondOrganizerId)
                .update();
        jdbcClient.sql("""
                        INSERT INTO group_account (
                            group_id, name, description, status, created_by_account_id
                        ) VALUES (:groupId, 'Race group', '', 'ACTIVE', :organizerId)
                        """)
                .param("groupId", groupId)
                .param("organizerId", organizerId)
                .update();
        jdbcClient.sql("""
                        INSERT INTO group_membership (group_id, account_id, role, status, joined_at)
                        VALUES (:groupId, :organizerId, 'ORGANIZER', 'ACTIVE', statement_timestamp()),
                               (:groupId, :secondOrganizerId, :secondOrganizerRole, 'ACTIVE', statement_timestamp())
                        """)
                .param("groupId", groupId)
                .param("organizerId", organizerId)
                .param("secondOrganizerId", secondOrganizerId)
                .param("secondOrganizerRole", secondOrganizerRole)
                .update();
        jdbcClient.sql("""
                        INSERT INTO planning_plan (
                            plan_id, group_id, title, state, created_by_account_id, version
                        ) VALUES (:planId, :groupId, 'Race plan', 'COLLABORATING', :organizerId, 1)
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
                            'Original notes.', '{"courtCount":2}'::jsonb
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
        for (var index = 0; index < 2; index++) {
            var providerId = UUID.randomUUID();
            providerIds.add(providerId);
            jdbcClient.sql("""
                            INSERT INTO provider_organization (
                                provider_id, display_name, status, verification_status,
                                version, eligibility_version
                            ) VALUES (:providerId, :displayName, 'ACTIVE', 'VERIFIED', 2, 2)
                            """)
                    .param("providerId", providerId)
                    .param("displayName", "Race provider " + index)
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
                organizerId,
                secondOrganizerId,
                groupId,
                planId,
                windowId,
                null,
                areaCode,
                startAt,
                correlationId,
                List.copyOf(providerIds));
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
                        ) VALUES (
                            :finalizationId, :planId, :basisPlanVersion,
                            :windowId, :startAt, :endAt,
                            :offerDeadline, 'COURT', 'Asia/Manila', :areaCode, 5,
                            4, 10, 'PHP', 100000, 250000, ARRAY['parking']::varchar[],
                            'Original notes.', '{"courtCount":2}'::jsonb, 0, 0,
                            ARRAY['NO_CURRENT_PREFERENCE_INPUT']::varchar[],
                            :organizerId, statement_timestamp()
                        )
                        """)
                .param("finalizationId", finalizationId)
                .param("planId", fixture.planId())
                .param("basisPlanVersion", basisPlanVersion)
                .param("windowId", fixture.windowId())
                .param("startAt", fixture.startAt())
                .param("endAt", fixture.startAt().plusHours(2))
                .param("offerDeadline", fixture.startAt().minusHours(1))
                .param("areaCode", fixture.areaCode())
                .param("organizerId", fixture.organizerId())
                .update();
        return finalizationId;
    }

    private PublishRequestCommand publishCommand(
            Fixture fixture, UUID finalizationId, long expectedPlanVersion, String key) {
        return new PublishRequestCommand(
                fixture.organizerId(),
                fixture.planId(),
                finalizationId,
                expectedPlanVersion,
                key,
                fixture.correlationId());
    }

    private TransferOrganizerCommand transferCommand(Fixture fixture, String key) {
        return new TransferOrganizerCommand(
                fixture.organizerId(),
                fixture.groupId(),
                fixture.secondOrganizerId(),
                1,
                key,
                fixture.correlationId());
    }

    private RemoveGroupMemberCommand removalCommand(Fixture fixture, String key) {
        return new RemoveGroupMemberCommand(
                fixture.secondOrganizerId(),
                fixture.groupId(),
                fixture.organizerId(),
                key,
                fixture.correlationId());
    }

    private ReplaceRequirementsCommand replacementCommand(Fixture fixture) {
        return new ReplaceRequirementsCommand(
                fixture.organizerId(),
                fixture.planId(),
                1,
                new RequirementReplacementRequest(
                        "Replacement draft",
                        ActivityCategory.COURT,
                        "Asia/Manila",
                        List.of(new RequirementReplacementRequest.CandidateWindowRequest(
                                fixture.windowId(),
                                fixture.startAt().plusMinutes(30),
                                fixture.startAt().plusHours(2).plusMinutes(30))),
                        new CreatePlanRequest.AreaRequest(fixture.areaCode(), 8),
                        new CreatePlanRequest.HeadcountRequest(5, 12),
                        new CreatePlanRequest.BudgetRequest("PHP", "1200.00", "3000.00"),
                        List.of("covered parking"),
                        "Replacement notes.",
                        Map.of()),
                fixture.correlationId());
    }

    private ClosePublishedRequestCommand closeCommand(Fixture fixture, UUID requestId, String key) {
        return new ClosePublishedRequestCommand(
                fixture.organizerId(), requestId, 2, key, fixture.correlationId());
    }

    private CancelPlanCommand cancelCommand(Fixture fixture, String key) {
        return new CancelPlanCommand(
                fixture.organizerId(), fixture.planId(), 2, key, fixture.correlationId());
    }

    private UUID currentRequestId(Fixture fixture) {
        return jdbcClient.sql("SELECT current_request_id FROM planning_plan WHERE plan_id = :planId")
                .param("planId", fixture.planId())
                .query(UUID.class)
                .single();
    }

    private <T> T success(ConcurrentDatabaseWorkers.Outcome<Object> outcome, Class<T> type) {
        assertThat(outcome.failure()).isNull();
        assertThat(outcome.value()).isInstanceOf(type);
        return type.cast(outcome.value());
    }

    private void assertPublicationFailure(
            ConcurrentDatabaseWorkers.Outcome<Object> outcome,
            RequestPublicationException.Reason reason) {
        assertThat(outcome.failure()).isInstanceOf(RequestPublicationException.class);
        assertThat(((RequestPublicationException) outcome.failure()).reason()).isEqualTo(reason);
    }

    private void assertRequirementFailure(
            ConcurrentDatabaseWorkers.Outcome<Object> outcome,
            RequirementReplacementException.Reason reason) {
        assertThat(outcome.failure()).isInstanceOf(RequirementReplacementException.class);
        assertThat(((RequirementReplacementException) outcome.failure()).reason()).isEqualTo(reason);
    }

    private void assertClosureFailure(
            ConcurrentDatabaseWorkers.Outcome<Object> outcome,
            RequestClosureException.Reason reason) {
        assertThat(outcome.failure()).isInstanceOf(RequestClosureException.class);
        assertThat(((RequestClosureException) outcome.failure()).reason()).isEqualTo(reason);
    }

    private void assertCancellationFailure(
            ConcurrentDatabaseWorkers.Outcome<Object> outcome,
            PlanCancellationException.Reason reason) {
        assertThat(outcome.failure()).isInstanceOf(PlanCancellationException.class);
        assertThat(((PlanCancellationException) outcome.failure()).reason()).isEqualTo(reason);
    }

    private void assertPlan(
            Fixture fixture,
            String title,
            String state,
            UUID currentRequestId,
            long version) {
        var actual = jdbcClient.sql("""
                        SELECT title, state, current_request_id, version
                        FROM planning_plan
                        WHERE plan_id = :planId
                        """)
                .param("planId", fixture.planId())
                .query((resultSet, rowNumber) -> new PlanRow(
                        resultSet.getString("title"),
                        resultSet.getString("state"),
                        resultSet.getObject("current_request_id", UUID.class),
                        resultSet.getLong("version")))
                .single();
        assertThat(actual).isEqualTo(new PlanRow(title, state, currentRequestId, version));
    }

    private void assertOriginalDraft(Fixture fixture) {
        assertDraft(
                fixture,
                5,
                4,
                10,
                100000L,
                250000L,
                "Original notes.",
                fixture.startAt(),
                fixture.startAt().plusHours(2),
                "parking");
    }

    private void assertReplacementDraft(Fixture fixture) {
        assertDraft(
                fixture,
                8,
                5,
                12,
                120000L,
                300000L,
                "Replacement notes.",
                fixture.startAt().plusMinutes(30),
                fixture.startAt().plusHours(2).plusMinutes(30),
                "covered parking");
    }

    private void assertDraft(
            Fixture fixture,
            int radiusKm,
            int minimumHeadcount,
            int maximumHeadcount,
            long budgetMinimum,
            long budgetMaximum,
            String notes,
            OffsetDateTime startsAt,
            OffsetDateTime endsAt,
            String mustHave) {
        var actual = jdbcClient.sql("""
                        SELECT d.radius_km, d.minimum_headcount, d.maximum_headcount,
                               d.budget_minimum_minor_units, d.budget_maximum_minor_units,
                               d.provider_safe_notes, w.starts_at, w.ends_at, m.must_have
                        FROM planning_requirement_draft d
                        JOIN planning_candidate_window w ON w.plan_id = d.plan_id AND w.retired_at IS NULL
                        JOIN planning_requirement_must_have m ON m.plan_id = d.plan_id
                        WHERE d.plan_id = :planId
                        """)
                .param("planId", fixture.planId())
                .query((resultSet, rowNumber) -> new DraftRow(
                        resultSet.getInt("radius_km"),
                        resultSet.getInt("minimum_headcount"),
                        resultSet.getInt("maximum_headcount"),
                        resultSet.getLong("budget_minimum_minor_units"),
                        resultSet.getLong("budget_maximum_minor_units"),
                        resultSet.getString("provider_safe_notes"),
                        resultSet.getObject("starts_at", OffsetDateTime.class),
                        resultSet.getObject("ends_at", OffsetDateTime.class),
                        resultSet.getString("must_have")))
                .single();
        assertThat(actual).isEqualTo(new DraftRow(
                radiusKm,
                minimumHeadcount,
                maximumHeadcount,
                budgetMinimum,
                budgetMaximum,
                notes,
                startsAt,
                endsAt,
                mustHave));
    }

    private void assertRequests(Fixture fixture, RequestRow... expected) {
        var actual = jdbcClient.sql("""
                        SELECT request_id, request_version, state, closed_at IS NOT NULL AS terminal,
                               area_code, provider_safe_notes
                        FROM planning_published_request
                        WHERE plan_id = :planId
                        ORDER BY request_version
                        """)
                .param("planId", fixture.planId())
                .query((resultSet, rowNumber) -> new RequestRow(
                        resultSet.getObject("request_id", UUID.class),
                        resultSet.getLong("request_version"),
                        resultSet.getString("state"),
                        resultSet.getBoolean("terminal"),
                        resultSet.getString("area_code"),
                        resultSet.getString("provider_safe_notes")))
                .list();
        assertThat(actual).containsExactly(expected);
        assertThat(actual.stream().filter(row -> "OPEN".equals(row.state()))).hasSizeLessThanOrEqualTo(1);
    }

    private void assertFinalizations(Fixture fixture, FinalizationRef... expected) {
        var actual = jdbcClient.sql("""
                        SELECT finalization_id, basis_plan_version
                        FROM planning_requirement_finalization
                        WHERE plan_id = :planId
                        ORDER BY basis_plan_version, finalization_id
                        """)
                .param("planId", fixture.planId())
                .query((resultSet, rowNumber) -> new FinalizationRef(
                        resultSet.getObject("finalization_id", UUID.class),
                        resultSet.getLong("basis_plan_version")))
                .list();
        assertThat(actual).containsExactlyInAnyOrder(expected);
        assertThat(jdbcClient.sql("""
                        SELECT count(*)
                        FROM planning_requirement_finalization
                        WHERE plan_id = :planId
                          AND selected_candidate_window_id = :windowId
                          AND selected_starts_at = :startAt
                          AND selected_ends_at = :endAt
                          AND offer_deadline = :offerDeadline
                          AND provider_safe_notes = 'Original notes.'
                        """)
                .param("planId", fixture.planId())
                .param("windowId", fixture.windowId())
                .param("startAt", fixture.startAt())
                .param("endAt", fixture.startAt().plusHours(2))
                .param("offerDeadline", fixture.startAt().minusHours(1))
                .query(Long.class)
                .single()).isEqualTo((long) expected.length);
    }

    private void assertRecipients(Fixture fixture, UUID... requestIds) {
        var actual = jdbcClient.sql("""
                        SELECT rr.published_request_id, rr.provider_id,
                               rr.provider_eligibility_version, rr.source, rr.access_state
                        FROM marketplace_request_recipient rr
                        JOIN planning_published_request r ON r.request_id = rr.published_request_id
                        WHERE r.plan_id = :planId
                        ORDER BY rr.published_request_id, rr.provider_id
                        """)
                .param("planId", fixture.planId())
                .query((resultSet, rowNumber) -> new RecipientRow(
                        resultSet.getObject("published_request_id", UUID.class),
                        resultSet.getObject("provider_id", UUID.class),
                        resultSet.getLong("provider_eligibility_version"),
                        resultSet.getString("source"),
                        resultSet.getString("access_state")))
                .list();
        var expected = new ArrayList<RecipientRow>();
        for (var requestId : requestIds) {
            for (var providerId : fixture.providerIds()) {
                expected.add(new RecipientRow(requestId, providerId, 2, "MATCH_RULE", "ACTIVE"));
            }
        }
        assertThat(actual).containsExactlyInAnyOrderElementsOf(expected);
    }

    private void assertOutbox(Fixture fixture, EventRef... events) {
        var actual = jdbcClient.sql("""
                        SELECT business_key
                        FROM messaging_outbox
                        WHERE trace_id = :correlationId
                        ORDER BY business_key
                        """)
                .param("correlationId", fixture.correlationId())
                .query(String.class)
                .list();
        var expected = new ArrayList<String>();
        for (var event : events) {
            for (var providerId : fixture.providerIds()) {
                expected.add(event.type() + ":" + event.requestId() + ":" + providerId);
            }
        }
        assertThat(actual).containsExactlyElementsOf(expected.stream().sorted().toList());
    }

    private void assertAudit(Fixture fixture, AuditRef... expected) {
        var actual = jdbcClient.sql("""
                        SELECT action, subject_id, actor_id
                        FROM audit_event
                        WHERE plan_id = :planId OR group_id = :groupId
                        ORDER BY action, subject_id
                        """)
                .param("planId", fixture.planId())
                .param("groupId", fixture.groupId())
                .query((resultSet, rowNumber) -> new AuditRef(
                        resultSet.getString("action"),
                        resultSet.getObject("subject_id", UUID.class),
                        resultSet.getObject("actor_id", UUID.class)))
                .list();
        assertThat(actual).containsExactlyInAnyOrder(expected);
        assertThat(jdbcClient.sql("""
                        SELECT count(*)
                        FROM audit_event
                        WHERE (plan_id = :planId OR group_id = :groupId)
                          AND correlation_id = :correlationId
                        """)
                .param("planId", fixture.planId())
                .param("groupId", fixture.groupId())
                .param("correlationId", fixture.correlationId())
                .query(Long.class)
                .single()).isEqualTo((long) expected.length);
    }

    private void assertIdempotency(Fixture fixture, IdempotencyRef... expected) {
        var actual = jdbcClient.sql("""
                        SELECT actor_id, operation, idempotency_key, resource_id
                        FROM idempotency_record
                        WHERE actor_id IN (:organizerId, :secondOrganizerId)
                        ORDER BY actor_id, operation, idempotency_key
                        """)
                .param("organizerId", fixture.organizerId())
                .param("secondOrganizerId", fixture.secondOrganizerId())
                .query((resultSet, rowNumber) -> new IdempotencyRef(
                        resultSet.getObject("actor_id", UUID.class),
                        resultSet.getString("operation"),
                        resultSet.getString("idempotency_key"),
                        resultSet.getObject("resource_id", UUID.class)))
                .list();
        assertThat(actual).containsExactlyInAnyOrder(expected);
        assertThat(jdbcClient.sql("""
                        SELECT count(*)
                        FROM idempotency_record
                        WHERE actor_id IN (:organizerId, :secondOrganizerId)
                          AND state = 'COMPLETED'
                          AND response_status IS NOT NULL
                          AND replay_state IS NOT NULL
                          AND response_headers IS NOT NULL
                        """)
                .param("organizerId", fixture.organizerId())
                .param("secondOrganizerId", fixture.secondOrganizerId())
                .query(Long.class)
                .single()).isEqualTo((long) expected.length);
    }

    private void assertMemberships(
            Fixture fixture,
            String organizerRole,
            String organizerStatus,
            String secondRole,
            String secondStatus) {
        var actual = jdbcClient.sql("""
                        SELECT account_id, role, status
                        FROM group_membership
                        WHERE group_id = :groupId
                        ORDER BY account_id
                        """)
                .param("groupId", fixture.groupId())
                .query((resultSet, rowNumber) -> new MembershipRow(
                        resultSet.getObject("account_id", UUID.class),
                        resultSet.getString("role"),
                        resultSet.getString("status")))
                .list();
        assertThat(actual).containsExactlyInAnyOrder(
                new MembershipRow(fixture.organizerId(), organizerRole, organizerStatus),
                new MembershipRow(fixture.secondOrganizerId(), secondRole, secondStatus));
    }

    private record Fixture(
            UUID organizerId,
            UUID secondOrganizerId,
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
                    organizerId,
                    secondOrganizerId,
                    groupId,
                    planId,
                    windowId,
                    value,
                    areaCode,
                    startAt,
                    correlationId,
                    providerIds);
        }
    }

    private record PlanRow(String title, String state, UUID currentRequestId, long version) {
    }

    private record DraftRow(
            int radiusKm,
            int minimumHeadcount,
            int maximumHeadcount,
            long budgetMinimum,
            long budgetMaximum,
            String notes,
            OffsetDateTime startsAt,
            OffsetDateTime endsAt,
            String mustHave) {
    }

    private record RequestRow(
            UUID requestId,
            long requestVersion,
            String state,
            boolean terminal,
            String areaCode,
            String notes) {
    }

    private record FinalizationRef(UUID finalizationId, long basisPlanVersion) {
    }

    private record RecipientRow(
            UUID requestId,
            UUID providerId,
            long eligibilityVersion,
            String source,
            String accessState) {
    }

    private record EventRef(String type, UUID requestId) {
    }

    private record AuditRef(String action, UUID subjectId, UUID actorId) {
    }

    private record IdempotencyRef(UUID actorId, String operation, String key, UUID resourceId) {
    }

    private record MembershipRow(UUID accountId, String role, String status) {
    }
}

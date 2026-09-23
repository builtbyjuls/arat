package com.builtbyjuls.arat.planning.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.builtbyjuls.arat.PostgreSqlIntegrationTest;
import com.builtbyjuls.arat.planning.application.PlanPreferenceService;
import com.builtbyjuls.arat.planning.application.PlanQueryService;
import com.builtbyjuls.arat.planning.application.RequirementFinalizationService;
import com.builtbyjuls.arat.planning.application.RequirementReplacementService;
import com.builtbyjuls.arat.planning.domain.Attendance;
import com.builtbyjuls.arat.planning.domain.ActivityCategory;
import com.builtbyjuls.arat.planning.domain.CandidateWindow;
import com.builtbyjuls.arat.planning.domain.Plan;
import com.builtbyjuls.arat.planning.domain.PlanState;
import com.builtbyjuls.arat.planning.domain.PublishedRequestState;
import com.builtbyjuls.arat.planning.domain.RequirementDraft;
import com.builtbyjuls.arat.planning.domain.RequirementFinalization;
import com.builtbyjuls.arat.planning.infrastructure.PlanRepository;
import com.builtbyjuls.arat.planning.infrastructure.PublishedRequestRepository;
import com.builtbyjuls.arat.planning.infrastructure.RequirementFinalizationRepository;
import com.builtbyjuls.arat.testing.ConcurrentDatabaseWorkers;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(properties = "arat.test.planning-context=true")
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PlanningRequestAccessIT extends PostgreSqlIntegrationTest {

    private static final UUID OWNER_ID = UUID.fromString("30000000-0000-4000-8000-000000000032");
    private static final UUID MEMBER_ID = UUID.fromString("30000000-0000-4000-8000-000000000033");
    private static final UUID GROUP_ID = UUID.fromString("40000000-0000-4000-8000-000000000032");

    @Autowired
    private PlanningRequestAccess access;

    @Autowired
    private PlanRepository planRepository;

    @Autowired
    private PublishedRequestRepository requestRepository;

    @Autowired
    private RequirementFinalizationRepository finalizationRepository;

    @Autowired
    private RequirementReplacementService requirementReplacementService;

    @Autowired
    private PlanPreferenceService preferenceService;

    @Autowired
    private RequirementFinalizationService finalizationService;

    @Autowired
    private PlanQueryService planQueryService;

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private TransactionTemplate transactionTemplate;

    @BeforeEach
    void prepareOwnerAndGroup() {
        transactionTemplate = new TransactionTemplate(transactionManager);
        jdbcClient.sql("""
                        INSERT INTO identity_account (account_id, display_name)
                        VALUES (:ownerId, 'Planning boundary owner'), (:memberId, 'Planning boundary member')
                        ON CONFLICT (account_id) DO NOTHING
                        """)
                .param("ownerId", OWNER_ID)
                .param("memberId", MEMBER_ID)
                .update();
        jdbcClient.sql("""
                        INSERT INTO group_account (group_id, name, description, status, created_by_account_id)
                        VALUES (:groupId, 'Planning boundary group', '', 'ACTIVE', :ownerId)
                        ON CONFLICT (group_id) DO NOTHING
                        """)
                .param("groupId", GROUP_ID)
                .param("ownerId", OWNER_ID)
                .update();
        jdbcClient.sql("""
                        INSERT INTO group_membership (group_id, account_id, role, status, joined_at)
                        VALUES (:groupId, :ownerId, 'ORGANIZER', 'ACTIVE', statement_timestamp()),
                               (:groupId, :memberId, 'MEMBER', 'ACTIVE', statement_timestamp())
                        ON CONFLICT (group_id, account_id) DO NOTHING
                        """)
                .param("groupId", GROUP_ID)
                .param("ownerId", OWNER_ID)
                .param("memberId", MEMBER_ID)
                .update();
    }

    @Test
    void mutatingAndLockingApisRequireACallerTransaction() {
        var fixture = createFixture();
        var publish = publishCommand(fixture, UUID.randomUUID(), fixture.finalizationId(), 1);

        assertThatThrownBy(() -> access.preparePublication(
                new PrepareRequestPublicationCommand(fixture.planId(), fixture.finalizationId(), 1)))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> access.create(publish))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> access.supersede(publish))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> access.close(
                new CloseRequestVersionCommand(fixture.planId(), UUID.randomUUID(), 1)))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> access.cancel(new CancelPlanRequestCommand(fixture.planId(), 1)))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    void createsSupersedesClosesReopensAndCancelsCoherentVersions() {
        var fixture = createFixture();
        var firstRequestId = UUID.randomUUID();

        var preparation = inTransaction(() -> access.preparePublication(
                new PrepareRequestPublicationCommand(fixture.planId(), fixture.finalizationId(), 1)));
        assertThat(preparation.planVersion()).isEqualTo(1);
        assertThat(preparation.currentRequestId()).isNull();
        assertThat(preparation.terms().category()).isEqualTo("COURT");
        assertThat(preparation.terms().areaCode()).isEqualTo("BGC");

        var first = inTransaction(() -> access.create(
                publishCommand(fixture, firstRequestId, fixture.finalizationId(), 1)));
        assertThat(first.planVersion()).isEqualTo(2);
        assertThat(first.request().requestVersion()).isEqualTo(1);
        assertThat(first.request().state()).isEqualTo("OPEN");
        assertThat(first.request().actionable()).isTrue();
        assertPlan(fixture.planId(), PlanState.OPEN_FOR_OFFERS, firstRequestId, 2);

        var secondFinalizationId = createFinalization(fixture, 2);
        var secondRequestId = UUID.randomUUID();
        var second = inTransaction(() -> access.supersede(
                publishCommand(fixture, secondRequestId, secondFinalizationId, 2)));
        assertThat(second.planVersion()).isEqualTo(3);
        assertThat(second.previousRequest().requestId()).isEqualTo(firstRequestId);
        assertThat(second.previousRequest().state()).isEqualTo("SUPERSEDED");
        assertThat(second.previousRequest().actionable()).isFalse();
        assertThat(second.request().requestVersion()).isEqualTo(2);
        assertPlan(fixture.planId(), PlanState.OPEN_FOR_OFFERS, secondRequestId, 3);

        var closed = inTransaction(() -> access.close(
                new CloseRequestVersionCommand(fixture.planId(), secondRequestId, 3)));
        assertThat(closed.planVersion()).isEqualTo(4);
        assertThat(closed.request().state()).isEqualTo("CLOSED");
        assertPlan(fixture.planId(), PlanState.COLLABORATING, null, 4);

        var thirdFinalizationId = createFinalization(fixture, 4);
        var thirdRequestId = UUID.randomUUID();
        var third = inTransaction(() -> access.create(
                publishCommand(fixture, thirdRequestId, thirdFinalizationId, 4)));
        assertThat(third.request().requestVersion()).isEqualTo(3);
        assertPlan(fixture.planId(), PlanState.OPEN_FOR_OFFERS, thirdRequestId, 5);

        var cancelled = inTransaction(() -> access.cancel(
                new CancelPlanRequestCommand(fixture.planId(), 5)));
        assertThat(cancelled.plan().version()).isEqualTo(6);
        assertThat(cancelled.cancelledRequest().requestId()).isEqualTo(thirdRequestId);
        assertThat(cancelled.cancelledRequest().state()).isEqualTo("CANCELLED");
        assertPlan(fixture.planId(), PlanState.CANCELLED, null, 6);

        assertThat(requestRepository.findById(firstRequestId).orElseThrow().state())
                .isEqualTo(PublishedRequestState.SUPERSEDED);
        assertThat(requestRepository.findById(secondRequestId).orElseThrow().state())
                .isEqualTo(PublishedRequestState.CLOSED);
        assertThat(requestRepository.findById(thirdRequestId).orElseThrow().state())
                .isEqualTo(PublishedRequestState.CANCELLED);
    }

    @Test
    void cancelsACollaboratingPlanWithoutCreatingARequest() {
        var fixture = createFixture();

        var result = inTransaction(() -> access.cancel(
                new CancelPlanRequestCommand(fixture.planId(), 1)));

        assertThat(result.plan().version()).isEqualTo(2);
        assertThat(result.cancelledRequest()).isNull();
        assertPlan(fixture.planId(), PlanState.CANCELLED, null, 2);
        assertThat(requestCount(fixture.planId())).isZero();
    }

    @Test
    void rejectsIllegalStatesStaleVersionsAndMismatchedFinalizations() {
        var fixture = createFixture();
        var other = createFixture();
        var requestId = UUID.randomUUID();

        assertFailure(
                () -> inTransaction(() -> access.supersede(
                        publishCommand(fixture, requestId, fixture.finalizationId(), 1))),
                PlanningRequestTransitionException.Reason.INVALID_PLAN_STATE);
        assertFailure(
                () -> inTransaction(() -> access.create(
                        publishCommand(fixture, requestId, other.finalizationId(), 1))),
                PlanningRequestTransitionException.Reason.FINALIZATION_NOT_FOUND);

        inTransaction(() -> access.create(
                publishCommand(fixture, requestId, fixture.finalizationId(), 1)));

        assertFailure(
                () -> inTransaction(() -> access.create(
                        publishCommand(fixture, UUID.randomUUID(), fixture.finalizationId(), 2))),
                PlanningRequestTransitionException.Reason.INVALID_PLAN_STATE);
        assertFailure(
                () -> inTransaction(() -> access.close(
                        new CloseRequestVersionCommand(fixture.planId(), requestId, 1))),
                PlanningRequestTransitionException.Reason.PRECONDITION_FAILED);
        assertFailure(
                () -> inTransaction(() -> access.close(
                        new CloseRequestVersionCommand(fixture.planId(), UUID.randomUUID(), 2))),
                PlanningRequestTransitionException.Reason.PRIVATE_RESOURCE_NOT_FOUND);

        assertPlan(fixture.planId(), PlanState.OPEN_FOR_OFFERS, requestId, 2);
        assertThat(requestRepository.findById(requestId).orElseThrow().state())
                .isEqualTo(PublishedRequestState.OPEN);
    }

    @Test
    void rejectsAnElapsedFinalizationAfterTakingThePlanLock() {
        var fixture = createFixture();
        var elapsedFinalizationId = createFinalization(
                fixture, 1, OffsetDateTime.now().minusMinutes(1).withNano(0));

        assertFailure(
                () -> inTransaction(() -> access.preparePublication(
                        new PrepareRequestPublicationCommand(
                                fixture.planId(), elapsedFinalizationId, 1))),
                PlanningRequestTransitionException.Reason.DEADLINE_ELAPSED);
        assertThat(requestCount(fixture.planId())).isZero();
        assertPlan(fixture.planId(), PlanState.COLLABORATING, null, 1);
    }

    @Test
    void outerRollbackRemovesCreationAndRestoresSupersededState() {
        var fixture = createFixture();
        var firstRequestId = UUID.randomUUID();

        transactionTemplate.executeWithoutResult(status -> {
            access.create(publishCommand(fixture, firstRequestId, fixture.finalizationId(), 1));
            status.setRollbackOnly();
        });

        assertThat(requestRepository.findById(firstRequestId)).isEmpty();
        assertPlan(fixture.planId(), PlanState.COLLABORATING, null, 1);

        inTransaction(() -> access.create(
                publishCommand(fixture, firstRequestId, fixture.finalizationId(), 1)));
        var secondFinalizationId = createFinalization(fixture, 2);
        var secondRequestId = UUID.randomUUID();

        transactionTemplate.executeWithoutResult(status -> {
            access.supersede(publishCommand(fixture, secondRequestId, secondFinalizationId, 2));
            status.setRollbackOnly();
        });

        assertThat(requestRepository.findById(secondRequestId)).isEmpty();
        assertThat(requestRepository.findById(firstRequestId).orElseThrow().state())
                .isEqualTo(PublishedRequestState.OPEN);
        assertPlan(fixture.planId(), PlanState.OPEN_FOR_OFFERS, firstRequestId, 2);
    }

    @Test
    void resolvesIdentifiersWithoutLocksAndReturnsOnlyTheAllowlistedSnapshot() {
        var fixture = createFixture();
        var requestId = UUID.randomUUID();
        inTransaction(() -> access.create(
                publishCommand(fixture, requestId, fixture.finalizationId(), 1)));

        assertThat(access.resolvePlanGroupId(fixture.planId())).contains(GROUP_ID);
        assertThat(access.resolveRequest(requestId)).contains(
                new RequestTransitionIdentifiers(requestId, fixture.planId(), GROUP_ID));

        var snapshot = access.findProviderSafeSnapshot(requestId).orElseThrow();
        assertThat(snapshot.requestId()).isEqualTo(requestId);
        assertThat(snapshot.categoryAttributes()).containsEntry("courtCount", 2);
        assertThat(snapshot.mustHaves()).containsExactly("Parking", "Shower");
        assertThat(snapshot.providerSafeNotes()).isEqualTo("Indoor court preferred.");
        assertThat(snapshot.actionable()).isTrue();
    }

    @Test
    void keepsOpenRequestSnapshotSeparateFromRepeatedDraftPreferenceAndFinalizationChanges() {
        var fixture = createFixture();
        var requestId = UUID.randomUUID();
        inTransaction(() -> access.create(publishCommand(fixture, requestId, fixture.finalizationId(), 1)));
        var originalRequestBytes = requestSnapshot(requestId).getBytes(StandardCharsets.UTF_8);

        var firstDraftStart = fixture.startsAt().plusHours(1);
        requirementReplacementService.replace(replacement(fixture, 2, "Open draft one", firstDraftStart));
        preferenceService.create(new CreatePreferenceCommand(
                MEMBER_ID, fixture.planId(), preferenceRequest(3, fixture.candidateWindowId(), "draft one")));
        var replacedPreference = preferenceService.replace(new ReplacePreferenceCommand(
                MEMBER_ID, fixture.planId(), 1, preferenceRequest(3, fixture.candidateWindowId(), "draft one replacement")));
        assertThat(replacedPreference.basisPlanVersion()).isEqualTo(3);
        assertThat(replacedPreference.version()).isEqualTo(2);

        var secondDraftStart = fixture.startsAt().plusHours(2);
        requirementReplacementService.replace(replacement(fixture, 3, "Open draft two", secondDraftStart));

        var detail = planQueryService.findPrivate(fixture.planId(), MEMBER_ID);
        assertThat(detail.title()).isEqualTo("Open draft two");
        assertThat(detail.requirements().candidateWindows()).singleElement().satisfies(window -> {
            assertThat(window.id()).isEqualTo(fixture.candidateWindowId());
            assertThat(window.startAt()).isEqualTo(secondDraftStart);
        });
        assertThat(preferenceService.findAll(fixture.planId(), MEMBER_ID).items()).singleElement()
                .satisfies(preference -> assertThat(preference.current()).isFalse());

        var finalization = finalizationService.finalizeRequirements(new FinalizeRequirementsCommand(
                OWNER_ID, fixture.planId(), 4,
                new FinalizeRequirementsRequest(fixture.candidateWindowId(), secondDraftStart.minusHours(1)),
                "open-draft-finalization", "open-draft"));

        assertThat(finalization.basisPlanVersion()).isEqualTo(4);
        assertThat(finalization.selectedStartAt()).isEqualTo(secondDraftStart);
        assertPlan(fixture.planId(), PlanState.OPEN_FOR_OFFERS, requestId, 4);
        assertThat(requestSnapshot(requestId).getBytes(StandardCharsets.UTF_8)).containsExactly(originalRequestBytes);
    }

    @Test
    void rejectsAnOpenStatePreferenceWriterWhoseBasisLosesTheDraftRace() throws Exception {
        var fixture = createFixture();
        var requestId = UUID.randomUUID();
        inTransaction(() -> access.create(publishCommand(fixture, requestId, fixture.finalizationId(), 1)));
        var originalRequestBytes = requestSnapshot(requestId).getBytes(StandardCharsets.UTF_8);

        var outcomes = ConcurrentDatabaseWorkers.runOrdered(
                dataSource, transactionManager,
                "SELECT plan_id FROM planning_plan WHERE plan_id = ? FOR UPDATE", fixture.planId(),
                () -> requirementReplacementService.replace(
                        replacement(fixture, 2, "Open draft winner", fixture.startsAt().plusHours(1))),
                () -> preferenceService.create(new CreatePreferenceCommand(
                        MEMBER_ID, fixture.planId(), preferenceRequest(2, fixture.candidateWindowId(), "stale writer"))));

        assertThat(outcomes.getFirst().succeeded()).isTrue();
        assertThat(outcomes.getLast().failure()).isInstanceOf(PreferenceException.class)
                .extracting(failure -> ((PreferenceException) failure).reason())
                .isEqualTo(PreferenceException.Reason.REQUIREMENT_VERSION_CHANGED);
        assertPlan(fixture.planId(), PlanState.OPEN_FOR_OFFERS, requestId, 3);
        assertThat(requestSnapshot(requestId).getBytes(StandardCharsets.UTF_8)).containsExactly(originalRequestBytes);
    }

    @Test
    void allowsOneOpenStatePreferenceReplacementAndRejectsItsStaleEtagRacer() throws Exception {
        var fixture = createFixture();
        var requestId = UUID.randomUUID();
        inTransaction(() -> access.create(publishCommand(fixture, requestId, fixture.finalizationId(), 1)));
        preferenceService.create(new CreatePreferenceCommand(
                MEMBER_ID, fixture.planId(), preferenceRequest(2, fixture.candidateWindowId(), "initial preference")));
        var originalRequestBytes = requestSnapshot(requestId).getBytes(StandardCharsets.UTF_8);

        var outcomes = ConcurrentDatabaseWorkers.runOrdered(
                dataSource, transactionManager,
                "SELECT plan_id FROM planning_plan WHERE plan_id = ? FOR UPDATE", fixture.planId(),
                () -> preferenceService.replace(new ReplacePreferenceCommand(
                        MEMBER_ID, fixture.planId(), 1,
                        preferenceRequest(2, fixture.candidateWindowId(), "replacement winner"))),
                () -> preferenceService.replace(new ReplacePreferenceCommand(
                        MEMBER_ID, fixture.planId(), 1,
                        preferenceRequest(2, fixture.candidateWindowId(), "replacement loser"))));

        assertThat(outcomes.getFirst().value().basisPlanVersion()).isEqualTo(2);
        assertThat(outcomes.getFirst().value().version()).isEqualTo(2);
        assertThat(outcomes.getLast().failure()).isInstanceOf(PreferenceException.class)
                .extracting(failure -> ((PreferenceException) failure).reason())
                .isEqualTo(PreferenceException.Reason.PRECONDITION_FAILED);
        assertPlan(fixture.planId(), PlanState.OPEN_FOR_OFFERS, requestId, 2);
        assertThat(requestSnapshot(requestId).getBytes(StandardCharsets.UTF_8)).containsExactly(originalRequestBytes);
    }

    private ReplaceRequirementsCommand replacement(
            Fixture fixture, long expectedPlanVersion, String title, OffsetDateTime startsAt) {
        return new ReplaceRequirementsCommand(
                OWNER_ID, fixture.planId(), expectedPlanVersion,
                new RequirementReplacementRequest(
                        title, ActivityCategory.COURT, "Asia/Manila",
                        List.of(new RequirementReplacementRequest.CandidateWindowRequest(
                                fixture.candidateWindowId(), startsAt, startsAt.plusHours(2))),
                        new CreatePlanRequest.AreaRequest("BGC", 5),
                        new CreatePlanRequest.HeadcountRequest(4, 10),
                        null, List.of("parking"), title + " notes", Map.of()),
                "open-draft");
    }

    private CreatePreferenceRequest preferenceRequest(long basisPlanVersion, UUID windowId, String note) {
        return new CreatePreferenceRequest(
                basisPlanVersion, Attendance.JOINING, 0, List.of(windowId), null, List.of(note), note);
    }

    private String requestSnapshot(UUID requestId) {
        return jdbcClient.sql("SELECT row_to_json(request_row)::text FROM planning_published_request request_row WHERE request_id = :requestId")
                .param("requestId", requestId)
                .query(String.class)
                .single();
    }

    private Fixture createFixture() {
        var planId = UUID.randomUUID();
        var candidateWindowId = UUID.randomUUID();
        var now = OffsetDateTime.now().withNano(0);
        var startsAt = now.plusDays(3);
        inTransaction(() -> {
            planRepository.insert(
                    new Plan(planId, GROUP_ID, "Private plan title", PlanState.COLLABORATING,
                            OWNER_ID, 1, now, now),
                    new RequirementDraft(
                            planId, ActivityCategory.COURT, "Asia/Manila", "BGC", 5,
                            4, 10, 0L, 250000L, "Indoor court preferred.", "{\"courtCount\":2}"),
                    List.of(new CandidateWindow(candidateWindowId, planId, 1, startsAt, startsAt.plusHours(2), null)),
                    List.of("Parking", "Shower"));
            return null;
        });
        var fixture = new Fixture(planId, candidateWindowId, startsAt, null);
        return new Fixture(planId, candidateWindowId, startsAt, createFinalization(fixture, 1));
    }

    private UUID createFinalization(Fixture fixture, long basisPlanVersion) {
        return createFinalization(fixture, basisPlanVersion, fixture.startsAt().minusDays(1));
    }

    private UUID createFinalization(
            Fixture fixture, long basisPlanVersion, OffsetDateTime offerDeadline) {
        return inTransaction(() -> {
            var finalizationId = UUID.randomUUID();
            var createdAt = finalizationRepository.databaseDecisionTime();
            finalizationRepository.insert(new RequirementFinalization(
                    finalizationId,
                    fixture.planId(),
                    basisPlanVersion,
                    fixture.candidateWindowId(),
                    fixture.startsAt(),
                    fixture.startsAt().plusHours(2),
                    offerDeadline,
                    ActivityCategory.COURT,
                    "Asia/Manila",
                    "BGC",
                    5,
                    4,
                    10,
                    0L,
                    250000L,
                    List.of("Parking", "Shower"),
                    "Indoor court preferred.",
                    "{\"courtCount\":2}",
                    0,
                    0,
                    List.of(com.builtbyjuls.arat.planning.domain.FinalizationWarning.NO_CURRENT_PREFERENCE_INPUT),
                    OWNER_ID,
                    createdAt));
            return finalizationId;
        });
    }

    private PublishRequestVersionCommand publishCommand(
            Fixture fixture, UUID requestId, UUID finalizationId, long expectedPlanVersion) {
        return new PublishRequestVersionCommand(
                requestId, fixture.planId(), finalizationId, OWNER_ID, expectedPlanVersion);
    }

    private void assertPlan(
            UUID planId, PlanState state, UUID currentRequestId, long version) {
        var plan = inTransaction(() -> planRepository.lockPlan(planId));
        assertThat(plan.state()).isEqualTo(state);
        assertThat(plan.currentRequestId()).isEqualTo(currentRequestId);
        assertThat(plan.version()).isEqualTo(version);
    }

    private long requestCount(UUID planId) {
        return jdbcClient.sql("SELECT count(*) FROM planning_published_request WHERE plan_id = :planId")
                .param("planId", planId)
                .query(Long.class)
                .single();
    }

    private void assertFailure(
            Runnable operation, PlanningRequestTransitionException.Reason reason) {
        assertThatThrownBy(operation::run)
                .isInstanceOf(PlanningRequestTransitionException.class)
                .extracting(exception -> ((PlanningRequestTransitionException) exception).reason())
                .isEqualTo(reason);
    }

    private <T> T inTransaction(java.util.concurrent.Callable<T> work) {
        return transactionTemplate.execute(status -> {
            try {
                return work.call();
            } catch (RuntimeException exception) {
                throw exception;
            } catch (Exception exception) {
                throw new IllegalStateException(exception);
            }
        });
    }

    private record Fixture(
            UUID planId,
            UUID candidateWindowId,
            OffsetDateTime startsAt,
            UUID finalizationId) {
    }
}

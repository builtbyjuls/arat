package com.builtbyjuls.arat.planning.api;

import com.builtbyjuls.arat.planning.domain.Plan;
import com.builtbyjuls.arat.planning.domain.PlanState;
import com.builtbyjuls.arat.planning.domain.PublishedRequest;
import com.builtbyjuls.arat.planning.domain.PublishedRequestState;
import com.builtbyjuls.arat.planning.domain.RequestDistributionMode;
import com.builtbyjuls.arat.planning.domain.RequirementFinalization;
import com.builtbyjuls.arat.planning.infrastructure.PlanRepository;
import com.builtbyjuls.arat.planning.infrastructure.PublishedRequestRepository;
import com.builtbyjuls.arat.planning.infrastructure.RequirementFinalizationRepository;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Public Planning boundary for caller-owned request publication and lifecycle transactions.
 */
@Component
public class PlanningRequestAccess {

    private final PlanRepository planRepository;
    private final PublishedRequestRepository requestRepository;
    private final RequirementFinalizationRepository finalizationRepository;
    private final ObjectMapper objectMapper;

    public PlanningRequestAccess(
            PlanRepository planRepository,
            PublishedRequestRepository requestRepository,
            RequirementFinalizationRepository finalizationRepository,
            ObjectMapper objectMapper) {
        this.planRepository = planRepository;
        this.requestRepository = requestRepository;
        this.finalizationRepository = finalizationRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public Optional<UUID> resolvePlanGroupId(UUID planId) {
        return planRepository.findGroupId(requireId(planId, "planId"));
    }

    @Transactional(readOnly = true)
    public Optional<RequestTransitionIdentifiers> resolveRequest(UUID requestId) {
        return requestRepository.findOwner(requireId(requestId, "requestId"))
                .map(owner -> new RequestTransitionIdentifiers(
                        owner.requestId(), owner.planId(), owner.groupId()));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public RequestPublicationPreparation preparePublication(PrepareRequestPublicationCommand command) {
        requireCommand(command);
        var locked = lockPublicationState(command.planId(), command.expectedPlanVersion());
        var finalization = requireCurrentFinalization(
                command.finalizationId(), locked.plan(), requestRepository.databaseDecisionTime());
        return new RequestPublicationPreparation(
                locked.plan().version(),
                locked.currentRequest() == null ? null : locked.currentRequest().requestId(),
                terms(finalization));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public RequestVersionTransition create(PublishRequestVersionCommand command) {
        requireCommand(command);
        var locked = lockPublicationState(command.planId(), command.expectedPlanVersion());
        if (locked.plan().state() != PlanState.COLLABORATING || locked.currentRequest() != null) {
            throw failure(PlanningRequestTransitionException.Reason.INVALID_PLAN_STATE);
        }
        var decisionTime = requestRepository.databaseDecisionTime();
        var finalization = requireCurrentFinalization(command.finalizationId(), locked.plan(), decisionTime);
        var request = requestRepository.insert(newRequest(command, finalization, decisionTime));
        var plan = transitionPlan(
                locked.plan(), PlanState.COLLABORATING, null, PlanState.OPEN_FOR_OFFERS, request.requestId());
        return new RequestVersionTransition(plan.version(), snapshot(request, true), null);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public RequestVersionTransition supersede(PublishRequestVersionCommand command) {
        requireCommand(command);
        var locked = lockPublicationState(command.planId(), command.expectedPlanVersion());
        if (locked.plan().state() != PlanState.OPEN_FOR_OFFERS || locked.currentRequest() == null) {
            throw failure(PlanningRequestTransitionException.Reason.INVALID_PLAN_STATE);
        }
        var decisionTime = requestRepository.databaseDecisionTime();
        var finalization = requireCurrentFinalization(command.finalizationId(), locked.plan(), decisionTime);
        var previous = requestRepository.transitionOpen(
                        locked.currentRequest().requestId(),
                        locked.plan().planId(),
                        PublishedRequestState.SUPERSEDED,
                        decisionTime)
                .orElseThrow(() -> failure(PlanningRequestTransitionException.Reason.INVALID_REQUEST_STATE));
        var request = requestRepository.insert(newRequest(command, finalization, decisionTime));
        var plan = transitionPlan(
                locked.plan(),
                PlanState.OPEN_FOR_OFFERS,
                previous.requestId(),
                PlanState.OPEN_FOR_OFFERS,
                request.requestId());
        return new RequestVersionTransition(plan.version(), snapshot(request, true), snapshot(previous, false));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public RequestVersionTransition close(CloseRequestVersionCommand command) {
        requireCommand(command);
        var plan = lockPlan(command.planId());
        requirePlanVersion(plan, command.expectedPlanVersion());
        var request = requestRepository.lockById(command.requestId())
                .orElseThrow(() -> failure(PlanningRequestTransitionException.Reason.PRIVATE_RESOURCE_NOT_FOUND));
        requireCurrentOpenRequest(plan, request);
        var closed = requestRepository.transitionOpen(
                        request.requestId(), plan.planId(), PublishedRequestState.CLOSED,
                        requestRepository.databaseDecisionTime())
                .orElseThrow(() -> failure(PlanningRequestTransitionException.Reason.INVALID_REQUEST_STATE));
        var transitionedPlan = transitionPlan(
                plan, PlanState.OPEN_FOR_OFFERS, request.requestId(), PlanState.COLLABORATING, null);
        return new RequestVersionTransition(transitionedPlan.version(), snapshot(closed, false), null);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public PlanRequestCancellation cancel(CancelPlanRequestCommand command) {
        requireCommand(command);
        var plan = lockPlan(command.planId());
        requirePlanVersion(plan, command.expectedPlanVersion());
        if (plan.state() == PlanState.COLLABORATING && plan.currentRequestId() == null) {
            var cancelledPlan = transitionPlan(
                    plan, PlanState.COLLABORATING, null, PlanState.CANCELLED, null);
            return new PlanRequestCancellation(cancelledPlan.version(), null);
        }
        if (plan.state() != PlanState.OPEN_FOR_OFFERS || plan.currentRequestId() == null) {
            throw failure(PlanningRequestTransitionException.Reason.INVALID_PLAN_STATE);
        }
        var request = requestRepository.lockById(plan.currentRequestId())
                .orElseThrow(() -> failure(PlanningRequestTransitionException.Reason.INVALID_REQUEST_STATE));
        requireCurrentOpenRequest(plan, request);
        var cancelledRequest = requestRepository.transitionOpen(
                        request.requestId(), plan.planId(), PublishedRequestState.CANCELLED,
                        requestRepository.databaseDecisionTime())
                .orElseThrow(() -> failure(PlanningRequestTransitionException.Reason.INVALID_REQUEST_STATE));
        var cancelledPlan = transitionPlan(
                plan, PlanState.OPEN_FOR_OFFERS, request.requestId(), PlanState.CANCELLED, null);
        return new PlanRequestCancellation(cancelledPlan.version(), snapshot(cancelledRequest, false));
    }

    @Transactional(readOnly = true)
    public Optional<ProviderSafeRequestSnapshot> findProviderSafeSnapshot(UUID requestId) {
        return requestRepository.findObservationById(requireId(requestId, "requestId"))
                .map(observation -> snapshot(observation.request(), observation.actionable()));
    }

    @Transactional(readOnly = true)
    public Optional<ProviderSafeRequestSnapshot> findOriginalPublicationSnapshot(UUID requestId) {
        return requestRepository.findById(requireId(requestId, "requestId"))
                .map(request -> snapshot(request, "OPEN", true));
    }

    private LockedPublicationState lockPublicationState(UUID planId, long expectedPlanVersion) {
        var plan = lockPlan(planId);
        requirePlanVersion(plan, expectedPlanVersion);
        if (plan.state() != PlanState.COLLABORATING && plan.state() != PlanState.OPEN_FOR_OFFERS) {
            throw failure(PlanningRequestTransitionException.Reason.INVALID_PLAN_STATE);
        }
        PublishedRequest currentRequest = null;
        if (plan.currentRequestId() != null) {
            currentRequest = requestRepository.lockById(plan.currentRequestId())
                    .orElseThrow(() -> failure(PlanningRequestTransitionException.Reason.INVALID_REQUEST_STATE));
            requireCurrentOpenRequest(plan, currentRequest);
        } else if (plan.state() == PlanState.OPEN_FOR_OFFERS) {
            throw failure(PlanningRequestTransitionException.Reason.INVALID_PLAN_STATE);
        }
        return new LockedPublicationState(plan, currentRequest);
    }

    private Plan lockPlan(UUID planId) {
        return planRepository.lockPlanIfPresent(planId)
                .orElseThrow(() -> failure(PlanningRequestTransitionException.Reason.PRIVATE_RESOURCE_NOT_FOUND));
    }

    private void requirePlanVersion(Plan plan, long expectedPlanVersion) {
        if (plan.version() != expectedPlanVersion) {
            throw failure(PlanningRequestTransitionException.Reason.PRECONDITION_FAILED);
        }
    }

    private RequirementFinalization requireCurrentFinalization(
            UUID finalizationId, Plan plan, OffsetDateTime decisionTime) {
        var finalization = finalizationRepository.findById(finalizationId)
                .orElseThrow(() -> failure(PlanningRequestTransitionException.Reason.FINALIZATION_NOT_FOUND));
        if (!finalization.planId().equals(plan.planId())) {
            throw failure(PlanningRequestTransitionException.Reason.FINALIZATION_NOT_FOUND);
        }
        if (finalization.basisPlanVersion() != plan.version()) {
            throw failure(PlanningRequestTransitionException.Reason.FINALIZATION_VERSION_CHANGED);
        }
        if (!decisionTime.isBefore(finalization.offerDeadline())) {
            throw failure(PlanningRequestTransitionException.Reason.DEADLINE_ELAPSED);
        }
        return finalization;
    }

    private void requireCurrentOpenRequest(Plan plan, PublishedRequest request) {
        if (plan.state() != PlanState.OPEN_FOR_OFFERS
                || !request.planId().equals(plan.planId())
                || !request.requestId().equals(plan.currentRequestId())
                || request.state() != PublishedRequestState.OPEN) {
            throw failure(PlanningRequestTransitionException.Reason.INVALID_REQUEST_STATE);
        }
    }

    private PublishedRequest newRequest(
            PublishRequestVersionCommand command,
            RequirementFinalization finalization,
            OffsetDateTime decisionTime) {
        return new PublishedRequest(
                command.requestId(),
                command.planId(),
                requestRepository.allocateNextVersion(command.planId()),
                PublishedRequestState.OPEN,
                RequestDistributionMode.MATCHED_POOL,
                finalization.category(),
                finalization.timeZone(),
                finalization.areaCode(),
                finalization.radiusKm(),
                finalization.selectedStartsAt(),
                finalization.selectedEndsAt(),
                finalization.minimumHeadcount(),
                finalization.maximumHeadcount(),
                finalization.budgetMinimumMinorUnits(),
                finalization.budgetMaximumMinorUnits(),
                finalization.mustHaves(),
                finalization.providerSafeNotes(),
                finalization.categoryAttributes(),
                finalization.offerDeadline(),
                command.publishedByAccountId(),
                decisionTime,
                null);
    }

    private Plan transitionPlan(
            Plan plan,
            PlanState expectedState,
            UUID expectedCurrentRequestId,
            PlanState newState,
            UUID newCurrentRequestId) {
        return planRepository.transitionRequestPointerVersioned(
                        plan.planId(), plan.version(), expectedState, expectedCurrentRequestId,
                        newState, newCurrentRequestId)
                .orElseThrow(() -> failure(PlanningRequestTransitionException.Reason.PRECONDITION_FAILED));
    }

    private ProviderSafeRequestTerms terms(RequirementFinalization finalization) {
        return new ProviderSafeRequestTerms(
                finalization.category().name(),
                finalization.timeZone(),
                finalization.areaCode(),
                finalization.radiusKm(),
                finalization.selectedStartsAt(),
                finalization.selectedEndsAt(),
                finalization.minimumHeadcount(),
                finalization.maximumHeadcount(),
                finalization.budgetMinimumMinorUnits(),
                finalization.budgetMaximumMinorUnits(),
                finalization.mustHaves(),
                finalization.providerSafeNotes(),
                attributes(finalization.categoryAttributes()),
                finalization.offerDeadline());
    }

    private ProviderSafeRequestSnapshot snapshot(PublishedRequest request, boolean actionable) {
        return snapshot(request, request.state().name(), actionable);
    }

    private ProviderSafeRequestSnapshot snapshot(PublishedRequest request, String state, boolean actionable) {
        return new ProviderSafeRequestSnapshot(
                request.requestId(),
                request.requestVersion(),
                state,
                request.distributionMode().name(),
                request.category().name(),
                request.timeZone(),
                request.areaCode(),
                request.radiusKm(),
                request.requestedStartsAt(),
                request.requestedEndsAt(),
                request.minimumHeadcount(),
                request.maximumHeadcount(),
                request.budgetMinimumMinorUnits(),
                request.budgetMaximumMinorUnits(),
                request.mustHaves(),
                request.providerSafeNotes(),
                attributes(request.categoryAttributes()),
                request.offerDeadline(),
                request.publishedAt(),
                actionable);
    }

    private Map<String, Object> attributes(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<>() {
            });
        } catch (JacksonException exception) {
            throw new IllegalStateException("stored category attributes are invalid", exception);
        }
    }

    private UUID requireId(UUID value, String name) {
        if (value == null) {
            throw new IllegalArgumentException(name + " must not be null");
        }
        return value;
    }

    private void requireCommand(Object command) {
        if (command == null) {
            throw new IllegalArgumentException("command must not be null");
        }
    }

    private PlanningRequestTransitionException failure(PlanningRequestTransitionException.Reason reason) {
        return new PlanningRequestTransitionException(reason);
    }

    private record LockedPublicationState(Plan plan, PublishedRequest currentRequest) {
    }
}

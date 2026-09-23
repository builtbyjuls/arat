package com.builtbyjuls.arat.planning.application;

import com.builtbyjuls.arat.groups.api.GroupMembershipAccess;
import com.builtbyjuls.arat.planning.api.FinalizeRequirementsCommand;
import com.builtbyjuls.arat.planning.api.RequirementFinalizationException;
import com.builtbyjuls.arat.planning.api.RequirementFinalizationRepresentation;
import com.builtbyjuls.arat.planning.domain.FinalizationWarning;
import com.builtbyjuls.arat.planning.domain.PlanState;
import com.builtbyjuls.arat.planning.domain.RequirementFinalization;
import com.builtbyjuls.arat.planning.infrastructure.PlanPreferenceRepository;
import com.builtbyjuls.arat.planning.infrastructure.PlanRepository;
import com.builtbyjuls.arat.planning.infrastructure.RequirementFinalizationRepository;
import com.builtbyjuls.arat.platform.audit.AuditEvent;
import com.builtbyjuls.arat.platform.audit.AuditEventWriter;
import com.builtbyjuls.arat.platform.audit.AuditMetadata;
import com.builtbyjuls.arat.platform.idempotency.ClaimResult;
import com.builtbyjuls.arat.platform.idempotency.CompletedIdempotencyResponse;
import com.builtbyjuls.arat.platform.idempotency.IdempotencyKeyReusedException;
import com.builtbyjuls.arat.platform.idempotency.IdempotencyRepository;
import com.builtbyjuls.arat.platform.idempotency.IdempotencyScope;
import com.builtbyjuls.arat.platform.idempotency.ReplayState;
import com.builtbyjuls.arat.platform.idempotency.RequestFingerprint;
import com.builtbyjuls.arat.platform.idempotency.StoredReplayHeaders;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Service
public class RequirementFinalizationService {

    public static final String FINALIZE_REQUIREMENTS_OPERATION = "plans.requirements.finalize";

    private final GroupMembershipAccess groupMembershipAccess;
    private final PlanRepository planRepository;
    private final PlanPreferenceRepository preferenceRepository;
    private final RequirementFinalizationRepository finalizationRepository;
    private final IdempotencyRepository idempotencyRepository;
    private final AuditEventWriter auditEventWriter;
    private final ObjectMapper objectMapper;

    public RequirementFinalizationService(
            GroupMembershipAccess groupMembershipAccess,
            PlanRepository planRepository,
            PlanPreferenceRepository preferenceRepository,
            RequirementFinalizationRepository finalizationRepository,
            IdempotencyRepository idempotencyRepository,
            AuditEventWriter auditEventWriter,
            ObjectMapper objectMapper) {
        this.groupMembershipAccess = groupMembershipAccess;
        this.planRepository = planRepository;
        this.preferenceRepository = preferenceRepository;
        this.finalizationRepository = finalizationRepository;
        this.idempotencyRepository = idempotencyRepository;
        this.auditEventWriter = auditEventWriter;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public RequirementFinalizationRepresentation finalizeRequirements(FinalizeRequirementsCommand command) {
        var scope = new IdempotencyScope(command.actorId(), FINALIZE_REQUIREMENTS_OPERATION, command.idempotencyKey());
        var fingerprint = RequestFingerprint.fromCanonicalFields(Map.of(
                "planId", command.planId(),
                "expectedPlanVersion", command.expectedPlanVersion(),
                "candidateWindowId", command.request().candidateWindowId(),
                "offerDeadline", command.request().offerDeadline()));
        var claim = idempotencyRepository.claim(scope, fingerprint);
        if (claim instanceof ClaimResult.Replay replay) {
            return replay(replay.response());
        }
        rejectUnusableClaim(claim);

        var groupId = planRepository.findGroupId(command.planId())
                .orElseThrow(() -> failure(RequirementFinalizationException.Reason.PRIVATE_RESOURCE_NOT_FOUND));
        if (!groupMembershipAccess.lockGroup(groupId)) {
            throw failure(RequirementFinalizationException.Reason.PRIVATE_RESOURCE_NOT_FOUND);
        }
        var plan = planRepository.lockPlan(command.planId());
        if (!groupMembershipAccess.hasActiveOrganizer(groupId, command.actorId())) {
            if (groupMembershipAccess.hasActiveMembership(groupId, command.actorId())) {
                throw failure(RequirementFinalizationException.Reason.FORBIDDEN_ROLE);
            }
            throw failure(RequirementFinalizationException.Reason.PRIVATE_RESOURCE_NOT_FOUND);
        }
        if (plan.version() != command.expectedPlanVersion()) {
            throw failure(RequirementFinalizationException.Reason.PRECONDITION_FAILED);
        }
        if (plan.state() != PlanState.COLLABORATING && plan.state() != PlanState.OPEN_FOR_OFFERS) {
            throw failure(RequirementFinalizationException.Reason.INVALID_PLAN_STATE);
        }

        var privatePlan = planRepository.findPrivate(command.planId(), groupId)
                .orElseThrow(() -> new IllegalStateException("plan disappeared under lock"));
        var selectedWindow = privatePlan.candidateWindows().stream()
                .filter(window -> window.candidateWindowId().equals(command.request().candidateWindowId()))
                .findFirst()
                .orElseThrow(() -> failure(RequirementFinalizationException.Reason.VALIDATION_FAILED));
        var deadline = command.request().offerDeadline();
        if (deadline.getNano() % 1_000 != 0) {
            throw failure(RequirementFinalizationException.Reason.VALIDATION_FAILED);
        }
        var decisionTime = finalizationRepository.databaseDecisionTime();
        if (!decisionTime.isBefore(deadline)
                || !deadline.isBefore(selectedWindow.startsAt())) {
            throw failure(RequirementFinalizationException.Reason.VALIDATION_FAILED);
        }
        var counts = preferenceRepository.countByPlanAndBasisVersion(command.planId(), plan.version());
        var warnings = warnings(counts);
        var draft = privatePlan.requirementDraft();
        var finalization = finalizationRepository.insert(new RequirementFinalization(
                UUID.randomUUID(), command.planId(), plan.version(), selectedWindow.candidateWindowId(),
                selectedWindow.startsAt(), selectedWindow.endsAt(), deadline,
                draft.category(), draft.timeZone(), draft.areaCode(), draft.radiusKm(), draft.minimumHeadcount(),
                draft.maximumHeadcount(), draft.budgetMinimumMinorUnits(), draft.budgetMaximumMinorUnits(),
                privatePlan.mustHaves(), draft.providerSafeNotes(), draft.categoryAttributes(), counts.current(), counts.stale(),
                warnings, command.actorId(), decisionTime));
        auditEventWriter.append(new AuditEvent(
                UUID.randomUUID(), command.actorId(), "plan.requirements.finalized", "requirement_finalization",
                finalization.finalizationId(), groupId, command.planId(), command.correlationId(), AuditMetadata.empty()));
        var representation = RequirementFinalizationRepresentation.from(finalization, objectMapper);
        idempotencyRepository.complete(scope, new CompletedIdempotencyResponse(
                201,
                ReplayState.from(objectMapper.valueToTree(Map.of("finalizationId", finalization.finalizationId())), objectMapper),
                StoredReplayHeaders.from(Map.of(
                        "ETag", PlanCreationService.etag(plan.version()),
                        "Location", location(command.planId(), finalization.finalizationId()))),
                finalization.finalizationId()));
        return representation;
    }

    private RequirementFinalizationRepresentation replay(CompletedIdempotencyResponse response) {
        var finalizationId = response.resourceIdOptional().orElseGet(() -> replayFinalizationId(response));
        var finalization = finalizationRepository.findById(finalizationId)
                .orElseThrow(() -> new IllegalStateException("completed finalization replay resource is missing"));
        return RequirementFinalizationRepresentation.from(finalization, objectMapper);
    }

    private UUID replayFinalizationId(CompletedIdempotencyResponse response) {
        try {
            return objectMapper.treeToValue(response.replayState().value().path("finalizationId"), UUID.class);
        } catch (JacksonException exception) {
            throw new IllegalStateException("stored finalization replay state is invalid", exception);
        }
    }

    private List<FinalizationWarning> warnings(PlanPreferenceRepository.PreferenceCounts counts) {
        var warnings = new java.util.ArrayList<FinalizationWarning>();
        if (counts.current() == 0) {
            warnings.add(FinalizationWarning.NO_CURRENT_PREFERENCE_INPUT);
        }
        if (counts.stale() > 0) {
            warnings.add(FinalizationWarning.STALE_PREFERENCE_INPUT_PRESENT);
        }
        return warnings;
    }

    private String location(UUID planId, UUID finalizationId) {
        return "/api/v1/plans/" + planId + "/requirement-finalization/" + finalizationId;
    }

    private void rejectUnusableClaim(ClaimResult claim) {
        if (claim instanceof ClaimResult.ConflictingFingerprint) {
            throw new IdempotencyKeyReusedException();
        }
        if (claim instanceof ClaimResult.InProgress) {
            throw new IllegalStateException("idempotency command is still in progress");
        }
    }

    private RequirementFinalizationException failure(RequirementFinalizationException.Reason reason) {
        return new RequirementFinalizationException(reason);
    }
}

package com.builtbyjuls.arat.planning.application;

import com.builtbyjuls.arat.groups.api.GroupMembershipAccess;
import com.builtbyjuls.arat.planning.api.CancelPlanCommand;
import com.builtbyjuls.arat.planning.api.PlanCancellationException;
import com.builtbyjuls.arat.planning.api.PlanRepresentation;
import com.builtbyjuls.arat.planning.domain.PlanState;
import com.builtbyjuls.arat.planning.infrastructure.PlanRepository;
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
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Service
public class PlanCancellationService {

    public static final String CANCEL_PLAN_OPERATION = "plans.cancel";

    private final GroupMembershipAccess groupMembershipAccess;
    private final PlanRepository planRepository;
    private final IdempotencyRepository idempotencyRepository;
    private final AuditEventWriter auditEventWriter;
    private final ObjectMapper objectMapper;

    public PlanCancellationService(
            GroupMembershipAccess groupMembershipAccess,
            PlanRepository planRepository,
            IdempotencyRepository idempotencyRepository,
            AuditEventWriter auditEventWriter,
            ObjectMapper objectMapper) {
        this.groupMembershipAccess = groupMembershipAccess;
        this.planRepository = planRepository;
        this.idempotencyRepository = idempotencyRepository;
        this.auditEventWriter = auditEventWriter;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public PlanRepresentation cancel(CancelPlanCommand command) {
        var scope = new IdempotencyScope(command.actorId(), CANCEL_PLAN_OPERATION, command.idempotencyKey());
        var claim = idempotencyRepository.claim(scope, RequestFingerprint.fromCanonicalFields(Map.of(
                "planId", command.planId(),
                "expectedPlanVersion", command.expectedPlanVersion())));
        if (claim instanceof ClaimResult.Replay replay) {
            return responseFrom(replay.response());
        }
        rejectUnusableClaim(claim);

        var groupId = planRepository.findGroupId(command.planId())
                .orElseThrow(() -> failure(PlanCancellationException.Reason.PRIVATE_RESOURCE_NOT_FOUND));
        if (!groupMembershipAccess.lockGroup(groupId)) {
            throw failure(PlanCancellationException.Reason.PRIVATE_RESOURCE_NOT_FOUND);
        }
        var plan = planRepository.lockPlan(command.planId());
        if (!groupMembershipAccess.hasActiveOrganizer(groupId, command.actorId())) {
            if (groupMembershipAccess.hasActiveMembership(groupId, command.actorId())) {
                throw failure(PlanCancellationException.Reason.FORBIDDEN_ROLE);
            }
            throw failure(PlanCancellationException.Reason.PRIVATE_RESOURCE_NOT_FOUND);
        }
        if (plan.version() != command.expectedPlanVersion()) {
            throw failure(PlanCancellationException.Reason.PRECONDITION_FAILED);
        }
        if (plan.state() != PlanState.COLLABORATING) {
            throw failure(PlanCancellationException.Reason.INVALID_PLAN_STATE);
        }

        var cancelledPlan = planRepository.cancelVersioned(command.planId(), command.expectedPlanVersion())
                .orElseThrow(() -> failure(PlanCancellationException.Reason.PRECONDITION_FAILED));
        auditEventWriter.append(new AuditEvent(
                UUID.randomUUID(),
                command.actorId(),
                "plan.cancelled",
                "plan",
                command.planId(),
                groupId,
                command.planId(),
                command.correlationId(),
                AuditMetadata.empty()));
        var representation = PlanRepresentation.from(cancelledPlan);
        idempotencyRepository.complete(scope, new CompletedIdempotencyResponse(
                200,
                ReplayState.from(objectMapper.valueToTree(representation), objectMapper),
                StoredReplayHeaders.from(Map.of("ETag", PlanCreationService.etag(cancelledPlan.version()))),
                cancelledPlan.planId()));
        return representation;
    }

    private PlanRepresentation responseFrom(CompletedIdempotencyResponse response) {
        try {
            return objectMapper.treeToValue(response.replayState().value(), PlanRepresentation.class);
        } catch (JacksonException exception) {
            throw new IllegalStateException("stored plan cancellation replay response is invalid", exception);
        }
    }

    private void rejectUnusableClaim(ClaimResult claim) {
        if (claim instanceof ClaimResult.ConflictingFingerprint) {
            throw new IdempotencyKeyReusedException();
        }
        if (claim instanceof ClaimResult.InProgress) {
            throw new IllegalStateException("idempotency command is still in progress");
        }
    }

    private PlanCancellationException failure(PlanCancellationException.Reason reason) {
        return new PlanCancellationException(reason);
    }
}

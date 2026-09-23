package com.builtbyjuls.arat.marketplace.application;

import com.builtbyjuls.arat.groups.api.GroupMembershipAccess;
import com.builtbyjuls.arat.marketplace.infrastructure.RequestRecipientRepository;
import com.builtbyjuls.arat.messaging.api.AppendOutboxEventCommand;
import com.builtbyjuls.arat.messaging.api.MessagingAccess;
import com.builtbyjuls.arat.messaging.api.OutboxEventEnvelope;
import com.builtbyjuls.arat.planning.api.CancelPlanCommand;
import com.builtbyjuls.arat.planning.api.CancelPlanRequestCommand;
import com.builtbyjuls.arat.planning.api.PlanCancellationException;
import com.builtbyjuls.arat.planning.api.PlanRepresentation;
import com.builtbyjuls.arat.planning.api.PlanRequestCancellation;
import com.builtbyjuls.arat.planning.api.PlanningRequestAccess;
import com.builtbyjuls.arat.planning.api.PlanningRequestTransitionException;
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
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Service
public class PlanCancellationService {

    public static final String CANCEL_PLAN_OPERATION = "plans.cancel";
    private static final String CANCELLED_EVENT_TYPE = "ProviderRequestCancelled";

    private final GroupMembershipAccess groupMembershipAccess;
    private final PlanningRequestAccess planningRequestAccess;
    private final RequestRecipientRepository recipientRepository;
    private final MessagingAccess messagingAccess;
    private final IdempotencyRepository idempotencyRepository;
    private final AuditEventWriter auditEventWriter;
    private final ObjectMapper objectMapper;

    public PlanCancellationService(
            GroupMembershipAccess groupMembershipAccess,
            PlanningRequestAccess planningRequestAccess,
            RequestRecipientRepository recipientRepository,
            MessagingAccess messagingAccess,
            IdempotencyRepository idempotencyRepository,
            AuditEventWriter auditEventWriter,
            ObjectMapper objectMapper) {
        this.groupMembershipAccess = groupMembershipAccess;
        this.planningRequestAccess = planningRequestAccess;
        this.recipientRepository = recipientRepository;
        this.messagingAccess = messagingAccess;
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

        var groupId = planningRequestAccess.resolvePlanGroupId(command.planId())
                .orElseThrow(() -> failure(PlanCancellationException.Reason.PRIVATE_RESOURCE_NOT_FOUND));
        if (!groupMembershipAccess.lockGroup(groupId)) {
            throw failure(PlanCancellationException.Reason.PRIVATE_RESOURCE_NOT_FOUND);
        }
        if (!groupMembershipAccess.hasActiveOrganizer(groupId, command.actorId())) {
            if (groupMembershipAccess.hasActiveMembership(groupId, command.actorId())) {
                throw failure(PlanCancellationException.Reason.FORBIDDEN_ROLE);
            }
            throw failure(PlanCancellationException.Reason.PRIVATE_RESOURCE_NOT_FOUND);
        }

        var cancellation = cancelPlan(command);
        appendTerminalEvents(cancellation, command.correlationId());
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

        var representation = cancellation.plan();
        idempotencyRepository.complete(scope, new CompletedIdempotencyResponse(
                200,
                ReplayState.from(objectMapper.valueToTree(representation), objectMapper),
                StoredReplayHeaders.from(Map.of("ETag", etag(representation.version()))),
                representation.planId()));
        return representation;
    }

    private PlanRequestCancellation cancelPlan(CancelPlanCommand command) {
        try {
            return planningRequestAccess.cancel(new CancelPlanRequestCommand(
                    command.planId(), command.expectedPlanVersion()));
        } catch (PlanningRequestTransitionException exception) {
            throw translate(exception);
        }
    }

    private void appendTerminalEvents(PlanRequestCancellation cancellation, String correlationId) {
        var request = cancellation.cancelledRequest();
        if (request == null) {
            return;
        }
        for (var providerId : recipientRepository.findProviderIdsByRequestId(request.requestId())) {
            appendEvent(
                    request.requestId(),
                    request.requestVersion(),
                    providerId,
                    cancellation.cancelledAt(),
                    correlationId);
        }
    }

    private void appendEvent(
            UUID requestId,
            long requestVersion,
            UUID providerId,
            OffsetDateTime occurredAt,
            String correlationId) {
        messagingAccess.append(new AppendOutboxEventCommand(
                UUID.randomUUID(),
                CANCELLED_EVENT_TYPE + ":" + requestId + ":" + providerId,
                new OutboxEventEnvelope(
                        CANCELLED_EVENT_TYPE,
                        1,
                        occurredAt,
                        "PublishedRequest",
                        requestId,
                        requestVersion,
                        correlationId,
                        payload(requestId, providerId))));
    }

    private String payload(UUID requestId, UUID providerId) {
        try {
            return objectMapper.writeValueAsString(Map.of(
                    "requestId", requestId,
                    "providerId", providerId));
        } catch (JacksonException exception) {
            throw new IllegalArgumentException("cancellation event payload cannot be serialized", exception);
        }
    }

    private PlanRepresentation responseFrom(CompletedIdempotencyResponse response) {
        try {
            return objectMapper.treeToValue(response.replayState().value(), PlanRepresentation.class);
        } catch (JacksonException exception) {
            throw new IllegalStateException("stored plan cancellation replay response is invalid", exception);
        }
    }

    private String etag(long version) {
        return "\"" + version + "\"";
    }

    private void rejectUnusableClaim(ClaimResult claim) {
        if (claim instanceof ClaimResult.ConflictingFingerprint) {
            throw new IdempotencyKeyReusedException();
        }
        if (claim instanceof ClaimResult.InProgress) {
            throw new IllegalStateException("idempotency command is still in progress");
        }
    }

    private PlanCancellationException translate(PlanningRequestTransitionException exception) {
        return switch (exception.reason()) {
            case PRIVATE_RESOURCE_NOT_FOUND -> failure(PlanCancellationException.Reason.PRIVATE_RESOURCE_NOT_FOUND);
            case PRECONDITION_FAILED -> failure(PlanCancellationException.Reason.PRECONDITION_FAILED);
            case INVALID_PLAN_STATE, INVALID_REQUEST_STATE ->
                    failure(PlanCancellationException.Reason.INVALID_PLAN_STATE);
            case FINALIZATION_NOT_FOUND, FINALIZATION_VERSION_CHANGED, DEADLINE_ELAPSED ->
                    throw new IllegalStateException("unexpected plan cancellation transition failure", exception);
        };
    }

    private PlanCancellationException failure(PlanCancellationException.Reason reason) {
        return new PlanCancellationException(reason);
    }
}

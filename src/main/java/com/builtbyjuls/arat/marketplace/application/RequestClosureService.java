package com.builtbyjuls.arat.marketplace.application;

import com.builtbyjuls.arat.groups.api.GroupMembershipAccess;
import com.builtbyjuls.arat.marketplace.api.ClosePublishedRequestCommand;
import com.builtbyjuls.arat.marketplace.api.PublishedRequestRepresentation;
import com.builtbyjuls.arat.marketplace.api.RequestClosureException;
import com.builtbyjuls.arat.marketplace.api.RequestClosureResponse;
import com.builtbyjuls.arat.marketplace.infrastructure.RequestRecipientRepository;
import com.builtbyjuls.arat.messaging.api.AppendOutboxEventCommand;
import com.builtbyjuls.arat.messaging.api.MessagingAccess;
import com.builtbyjuls.arat.messaging.api.OutboxEventEnvelope;
import com.builtbyjuls.arat.planning.api.CloseRequestVersionCommand;
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
public class RequestClosureService {

    public static final String CLOSE_REQUEST_OPERATION = "published-requests.close";
    private static final String CLOSED_EVENT_TYPE = "ProviderRequestClosed";

    private final GroupMembershipAccess groupMembershipAccess;
    private final PlanningRequestAccess planningRequestAccess;
    private final RequestRecipientRepository recipientRepository;
    private final MessagingAccess messagingAccess;
    private final IdempotencyRepository idempotencyRepository;
    private final AuditEventWriter auditEventWriter;
    private final ObjectMapper objectMapper;

    public RequestClosureService(
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
    public RequestClosureResponse close(ClosePublishedRequestCommand command) {
        var scope = new IdempotencyScope(
                command.actorId(), CLOSE_REQUEST_OPERATION, command.idempotencyKey());
        var fingerprint = RequestFingerprint.fromCanonicalFields(Map.of(
                "requestId", command.requestId(),
                "expectedPlanVersion", command.expectedPlanVersion()));
        var claim = idempotencyRepository.claim(scope, fingerprint);
        if (claim instanceof ClaimResult.Replay replay) {
            return replay(replay.response());
        }
        rejectUnusableClaim(claim);

        var identifiers = planningRequestAccess.resolveRequest(command.requestId())
                .orElseThrow(() -> failure(RequestClosureException.Reason.PRIVATE_RESOURCE_NOT_FOUND));
        if (!groupMembershipAccess.lockGroup(identifiers.groupId())) {
            throw failure(RequestClosureException.Reason.PRIVATE_RESOURCE_NOT_FOUND);
        }
        if (!groupMembershipAccess.hasActiveOrganizer(identifiers.groupId(), command.actorId())) {
            if (groupMembershipAccess.hasActiveMembership(identifiers.groupId(), command.actorId())) {
                throw failure(RequestClosureException.Reason.FORBIDDEN_ROLE);
            }
            throw failure(RequestClosureException.Reason.PRIVATE_RESOURCE_NOT_FOUND);
        }

        var transition = closeRequest(command, identifiers.planId());
        for (var recipient : recipientRepository.findByRequestId(command.requestId())) {
            appendEvent(
                    command.requestId(),
                    transition.request().requestVersion(),
                    recipient.providerId(),
                    transition.closedAt(),
                    command.correlationId());
        }
        auditEventWriter.append(new AuditEvent(
                UUID.randomUUID(),
                command.actorId(),
                "published_request.closed",
                "published_request",
                command.requestId(),
                identifiers.groupId(),
                identifiers.planId(),
                command.correlationId(),
                AuditMetadata.empty()));

        var etag = etag(transition.planVersion());
        idempotencyRepository.complete(scope, new CompletedIdempotencyResponse(
                200,
                ReplayState.from(objectMapper.createObjectNode(), objectMapper),
                StoredReplayHeaders.from(Map.of("ETag", etag)),
                command.requestId()));
        return new RequestClosureResponse(
                200, etag, PublishedRequestRepresentation.from(transition.request()));
    }

    private com.builtbyjuls.arat.planning.api.RequestClosureTransition closeRequest(
            ClosePublishedRequestCommand command, UUID planId) {
        try {
            return planningRequestAccess.close(new CloseRequestVersionCommand(
                    planId, command.requestId(), command.expectedPlanVersion()));
        } catch (PlanningRequestTransitionException exception) {
            throw translate(exception);
        }
    }

    private RequestClosureResponse replay(CompletedIdempotencyResponse response) {
        var requestId = response.resourceIdOptional()
                .orElseThrow(() -> new IllegalStateException("completed closure replay resource is missing"));
        var snapshot = planningRequestAccess.findProviderSafeSnapshot(requestId)
                .orElseThrow(() -> new IllegalStateException("completed closure replay resource is missing"));
        var etag = response.headers().values().get("ETag");
        if (etag == null) {
            throw new IllegalStateException("completed closure replay is missing ETag");
        }
        return new RequestClosureResponse(
                response.status(), etag, PublishedRequestRepresentation.from(snapshot));
    }

    private void appendEvent(
            UUID requestId,
            long requestVersion,
            UUID providerId,
            OffsetDateTime occurredAt,
            String correlationId) {
        messagingAccess.append(new AppendOutboxEventCommand(
                UUID.randomUUID(),
                CLOSED_EVENT_TYPE + ":" + requestId + ":" + providerId,
                new OutboxEventEnvelope(
                        CLOSED_EVENT_TYPE,
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
            throw new IllegalArgumentException("closure event payload cannot be serialized", exception);
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

    private RequestClosureException translate(PlanningRequestTransitionException exception) {
        return switch (exception.reason()) {
            case PRIVATE_RESOURCE_NOT_FOUND -> failure(RequestClosureException.Reason.PRIVATE_RESOURCE_NOT_FOUND);
            case PRECONDITION_FAILED -> failure(RequestClosureException.Reason.PRECONDITION_FAILED);
            case INVALID_PLAN_STATE, INVALID_REQUEST_STATE ->
                    failure(RequestClosureException.Reason.INVALID_REQUEST_STATE);
            case FINALIZATION_NOT_FOUND, FINALIZATION_VERSION_CHANGED, DEADLINE_ELAPSED ->
                    throw new IllegalStateException("unexpected closure transition failure", exception);
        };
    }

    private RequestClosureException failure(RequestClosureException.Reason reason) {
        return new RequestClosureException(reason);
    }
}

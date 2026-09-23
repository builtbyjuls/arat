package com.builtbyjuls.arat.marketplace.application;

import com.builtbyjuls.arat.groups.api.GroupMembershipAccess;
import com.builtbyjuls.arat.marketplace.api.PublishRequestCommand;
import com.builtbyjuls.arat.marketplace.api.PublishedRequestRepresentation;
import com.builtbyjuls.arat.marketplace.api.RequestPublicationException;
import com.builtbyjuls.arat.marketplace.api.RequestPublicationResponse;
import com.builtbyjuls.arat.marketplace.domain.RequestRecipient;
import com.builtbyjuls.arat.marketplace.domain.RequestRecipientAccessState;
import com.builtbyjuls.arat.marketplace.domain.RequestRecipientSource;
import com.builtbyjuls.arat.marketplace.infrastructure.RequestRecipientRepository;
import com.builtbyjuls.arat.matching.api.MatchRequestRecipientsCommand;
import com.builtbyjuls.arat.matching.api.MatchingAccess;
import com.builtbyjuls.arat.matching.api.RequestRecipientSelection;
import com.builtbyjuls.arat.messaging.api.AppendOutboxEventCommand;
import com.builtbyjuls.arat.messaging.api.MessagingAccess;
import com.builtbyjuls.arat.messaging.api.OutboxEventEnvelope;
import com.builtbyjuls.arat.planning.api.PlanningRequestAccess;
import com.builtbyjuls.arat.planning.api.PlanningRequestTransitionException;
import com.builtbyjuls.arat.planning.api.PrepareRequestPublicationCommand;
import com.builtbyjuls.arat.planning.api.PublishRequestVersionCommand;
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
public class RequestPublicationService {

    public static final String PUBLISH_REQUEST_OPERATION = "plans.published-requests.publish";
    private static final String EVENT_TYPE = "ProviderRequestPublished";

    private final GroupMembershipAccess groupMembershipAccess;
    private final PlanningRequestAccess planningRequestAccess;
    private final MatchingAccess matchingAccess;
    private final RequestRecipientRepository recipientRepository;
    private final MessagingAccess messagingAccess;
    private final IdempotencyRepository idempotencyRepository;
    private final AuditEventWriter auditEventWriter;
    private final ObjectMapper objectMapper;

    public RequestPublicationService(
            GroupMembershipAccess groupMembershipAccess,
            PlanningRequestAccess planningRequestAccess,
            MatchingAccess matchingAccess,
            RequestRecipientRepository recipientRepository,
            MessagingAccess messagingAccess,
            IdempotencyRepository idempotencyRepository,
            AuditEventWriter auditEventWriter,
            ObjectMapper objectMapper) {
        this.groupMembershipAccess = groupMembershipAccess;
        this.planningRequestAccess = planningRequestAccess;
        this.matchingAccess = matchingAccess;
        this.recipientRepository = recipientRepository;
        this.messagingAccess = messagingAccess;
        this.idempotencyRepository = idempotencyRepository;
        this.auditEventWriter = auditEventWriter;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public RequestPublicationResponse publish(PublishRequestCommand command) {
        var scope = new IdempotencyScope(
                command.actorId(), PUBLISH_REQUEST_OPERATION, command.idempotencyKey());
        var fingerprint = RequestFingerprint.fromCanonicalFields(Map.of(
                "planId", command.planId(),
                "finalizationId", command.finalizationId(),
                "expectedPlanVersion", command.expectedPlanVersion()));
        var claim = idempotencyRepository.claim(scope, fingerprint);
        if (claim instanceof ClaimResult.Replay replay) {
            return replay(replay.response());
        }
        rejectUnusableClaim(claim);

        var groupId = planningRequestAccess.resolvePlanGroupId(command.planId())
                .orElseThrow(() -> failure(RequestPublicationException.Reason.PRIVATE_RESOURCE_NOT_FOUND));
        if (!groupMembershipAccess.lockGroup(groupId)) {
            throw failure(RequestPublicationException.Reason.PRIVATE_RESOURCE_NOT_FOUND);
        }
        if (!groupMembershipAccess.hasActiveOrganizer(groupId, command.actorId())) {
            if (groupMembershipAccess.hasActiveMembership(groupId, command.actorId())) {
                throw failure(RequestPublicationException.Reason.FORBIDDEN_ROLE);
            }
            throw failure(RequestPublicationException.Reason.PRIVATE_RESOURCE_NOT_FOUND);
        }

        var preparation = prepare(command);
        if (preparation.currentRequestId() != null) {
            throw failure(RequestPublicationException.Reason.INVALID_PLAN_STATE);
        }
        var selection = matchingAccess.selectRecipients(new MatchRequestRecipientsCommand(
                preparation.terms().category(), preparation.terms().areaCode()));
        var candidates = switch (selection) {
            case RequestRecipientSelection.Candidates selected -> selected.candidates();
            case RequestRecipientSelection.NoCandidates ignored ->
                    throw failure(RequestPublicationException.Reason.NO_ELIGIBLE_PROVIDERS);
            case RequestRecipientSelection.CapExceeded ignored ->
                    throw failure(RequestPublicationException.Reason.RECIPIENT_LIMIT_EXCEEDED);
        };

        var requestId = UUID.randomUUID();
        var transition = create(command, requestId);
        var request = transition.request();
        for (var candidate : candidates) {
            recipientRepository.insert(new RequestRecipient(
                    requestId,
                    candidate.providerId(),
                    candidate.eligibilityVersion(),
                    RequestRecipientSource.MATCH_RULE,
                    null,
                    RequestRecipientAccessState.ACTIVE,
                    request.publishedAt()));
            messagingAccess.append(new AppendOutboxEventCommand(
                    UUID.randomUUID(),
                    businessKey(requestId, candidate.providerId()),
                    new OutboxEventEnvelope(
                            EVENT_TYPE,
                            1,
                            request.publishedAt(),
                            "PublishedRequest",
                            requestId,
                            request.requestVersion(),
                            command.correlationId(),
                            payload(requestId, candidate.providerId()))));
        }
        auditEventWriter.append(new AuditEvent(
                UUID.randomUUID(),
                command.actorId(),
                "published_request.published",
                "published_request",
                requestId,
                groupId,
                command.planId(),
                command.correlationId(),
                AuditMetadata.references(Map.of("finalizationId", command.finalizationId()))));

        var etag = etag(transition.planVersion());
        var location = location(command.planId(), requestId);
        idempotencyRepository.complete(scope, new CompletedIdempotencyResponse(
                201,
                ReplayState.from(objectMapper.createObjectNode(), objectMapper),
                StoredReplayHeaders.from(Map.of("ETag", etag, "Location", location)),
                requestId));
        return new RequestPublicationResponse(
                201, etag, location, PublishedRequestRepresentation.from(request));
    }

    private com.builtbyjuls.arat.planning.api.RequestPublicationPreparation prepare(
            PublishRequestCommand command) {
        try {
            return planningRequestAccess.preparePublication(new PrepareRequestPublicationCommand(
                    command.planId(), command.finalizationId(), command.expectedPlanVersion()));
        } catch (PlanningRequestTransitionException exception) {
            throw translate(exception);
        }
    }

    private com.builtbyjuls.arat.planning.api.RequestVersionTransition create(
            PublishRequestCommand command, UUID requestId) {
        try {
            return planningRequestAccess.create(new PublishRequestVersionCommand(
                    requestId,
                    command.planId(),
                    command.finalizationId(),
                    command.actorId(),
                    command.expectedPlanVersion()));
        } catch (PlanningRequestTransitionException exception) {
            throw translate(exception);
        }
    }

    private RequestPublicationResponse replay(CompletedIdempotencyResponse response) {
        var requestId = response.resourceIdOptional()
                .orElseThrow(() -> new IllegalStateException("completed publication replay resource is missing"));
        var snapshot = planningRequestAccess.findOriginalPublicationSnapshot(requestId)
                .orElseThrow(() -> new IllegalStateException("completed publication replay resource is missing"));
        var headers = response.headers().values();
        return new RequestPublicationResponse(
                response.status(),
                requiredHeader(headers, "ETag"),
                requiredHeader(headers, "Location"),
                PublishedRequestRepresentation.from(snapshot));
    }

    private String payload(UUID requestId, UUID providerId) {
        try {
            return objectMapper.writeValueAsString(Map.of(
                    "requestId", requestId,
                    "providerId", providerId));
        } catch (JacksonException exception) {
            throw new IllegalArgumentException("publication event payload cannot be serialized", exception);
        }
    }

    private String businessKey(UUID requestId, UUID providerId) {
        return EVENT_TYPE + ":" + requestId + ":" + providerId;
    }

    private String location(UUID planId, UUID requestId) {
        return "/api/v1/plans/" + planId + "/published-requests/" + requestId;
    }

    private String etag(long version) {
        return "\"" + version + "\"";
    }

    private String requiredHeader(Map<String, String> headers, String name) {
        var value = headers.get(name);
        if (value == null) {
            throw new IllegalStateException("completed publication replay is missing " + name);
        }
        return value;
    }

    private void rejectUnusableClaim(ClaimResult claim) {
        if (claim instanceof ClaimResult.ConflictingFingerprint) {
            throw new IdempotencyKeyReusedException();
        }
        if (claim instanceof ClaimResult.InProgress) {
            throw new IllegalStateException("idempotency command is still in progress");
        }
    }

    private RequestPublicationException translate(PlanningRequestTransitionException exception) {
        return switch (exception.reason()) {
            case PRIVATE_RESOURCE_NOT_FOUND, FINALIZATION_NOT_FOUND ->
                    failure(RequestPublicationException.Reason.PRIVATE_RESOURCE_NOT_FOUND);
            case PRECONDITION_FAILED -> failure(RequestPublicationException.Reason.PRECONDITION_FAILED);
            case INVALID_PLAN_STATE, INVALID_REQUEST_STATE ->
                    failure(RequestPublicationException.Reason.INVALID_PLAN_STATE);
            case FINALIZATION_VERSION_CHANGED ->
                    failure(RequestPublicationException.Reason.FINALIZATION_VERSION_CHANGED);
            case DEADLINE_ELAPSED -> failure(RequestPublicationException.Reason.REQUEST_DEADLINE_EXPIRED);
        };
    }

    private RequestPublicationException failure(RequestPublicationException.Reason reason) {
        return new RequestPublicationException(reason);
    }
}

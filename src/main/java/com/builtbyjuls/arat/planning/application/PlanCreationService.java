package com.builtbyjuls.arat.planning.application;

import com.builtbyjuls.arat.groups.api.GroupMembershipAccess;
import com.builtbyjuls.arat.planning.api.CreatePlanCommand;
import com.builtbyjuls.arat.planning.api.PlanCreationException;
import com.builtbyjuls.arat.planning.api.PlanRepresentation;
import com.builtbyjuls.arat.planning.domain.CandidateWindow;
import com.builtbyjuls.arat.planning.domain.RequirementDraft;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Service
public class PlanCreationService {

    public static final String CREATE_PLAN_OPERATION = "plans.create";

    private final GroupMembershipAccess groupMembershipAccess;
    private final PlanRepository planRepository;
    private final IdempotencyRepository idempotencyRepository;
    private final AuditEventWriter auditEventWriter;
    private final ObjectMapper objectMapper;

    public PlanCreationService(
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
    public PlanRepresentation create(CreatePlanCommand command) {
        var scope = new IdempotencyScope(command.actorId(), CREATE_PLAN_OPERATION, command.idempotencyKey());
        var fingerprint = RequestFingerprint.fromCanonicalFields(Map.of(
                "groupId", command.groupId(),
                "request", command.request()));
        var claim = idempotencyRepository.claim(scope, fingerprint);
        if (claim instanceof ClaimResult.Replay replay) {
            return responseFrom(replay.response());
        }
        rejectUnusableClaim(claim);

        if (!groupMembershipAccess.lockAndHasActiveMembership(command.groupId(), command.actorId())) {
            throw new PlanCreationException(PlanCreationException.Reason.PRIVATE_RESOURCE_NOT_FOUND);
        }

        var planId = UUID.randomUUID();
        var request = command.request();
        var requirementDraft = new RequirementDraft(
                planId,
                request.category(),
                request.timeZone(),
                request.area().code(),
                request.area().radiusKm(),
                request.headcount().minimum(),
                request.headcount().maximum(),
                request.budget() == null ? null : request.budget().minimumMinorUnits(),
                request.budget() == null ? null : request.budget().maximumMinorUnits(),
                request.providerSafeNotes(),
                categoryAttributesJson(request));
        var candidateWindows = candidateWindows(planId, command);
        var plan = planRepository.insertNew(
                planId,
                command.groupId(),
                request.title(),
                command.actorId(),
                requirementDraft,
                candidateWindows,
                request.mustHaves());
        auditEventWriter.append(new AuditEvent(
                UUID.randomUUID(),
                command.actorId(),
                "plan.created",
                "plan",
                plan.planId(),
                plan.groupId(),
                plan.planId(),
                command.correlationId(),
                AuditMetadata.empty()));

        var representation = PlanRepresentation.from(plan);
        idempotencyRepository.complete(scope, new CompletedIdempotencyResponse(
                201,
                ReplayState.from(objectMapper.valueToTree(representation), objectMapper),
                StoredReplayHeaders.from(Map.of(
                        "ETag", etag(plan.version()),
                        "Location", location(plan.planId()))),
                plan.planId()));
        return representation;
    }

    public static String etag(long version) {
        return "\"" + version + "\"";
    }

    public static String location(UUID planId) {
        return "/api/v1/plans/" + planId;
    }

    private List<CandidateWindow> candidateWindows(UUID planId, CreatePlanCommand command) {
        var windows = command.request().candidateWindows();
        return java.util.stream.IntStream.range(0, windows.size())
                .mapToObj(index -> {
                    var window = windows.get(index);
                    return new CandidateWindow(
                            UUID.randomUUID(), planId, index + 1, window.startAt(), window.endAt(), null);
                })
                .toList();
    }

    private String categoryAttributesJson(com.builtbyjuls.arat.planning.api.CreatePlanRequest request) {
        try {
            return objectMapper.writeValueAsString(request.categoryAttributes());
        } catch (JacksonException exception) {
            throw new IllegalArgumentException("categoryAttributes cannot be serialized", exception);
        }
    }

    private PlanRepresentation responseFrom(CompletedIdempotencyResponse response) {
        try {
            return objectMapper.treeToValue(response.replayState().value(), PlanRepresentation.class);
        } catch (JacksonException exception) {
            throw new IllegalStateException("stored plan replay response is invalid", exception);
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
}

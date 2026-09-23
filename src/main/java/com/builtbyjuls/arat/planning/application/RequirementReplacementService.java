package com.builtbyjuls.arat.planning.application;

import com.builtbyjuls.arat.groups.api.GroupMembershipAccess;
import com.builtbyjuls.arat.planning.api.ReplaceRequirementsCommand;
import com.builtbyjuls.arat.planning.api.RequirementReplacementException;
import com.builtbyjuls.arat.planning.api.RequirementRepresentation;
import com.builtbyjuls.arat.planning.domain.CandidateWindow;
import com.builtbyjuls.arat.planning.domain.PlanState;
import com.builtbyjuls.arat.planning.domain.RequirementDraft;
import com.builtbyjuls.arat.planning.infrastructure.PlanRepository;
import com.builtbyjuls.arat.platform.audit.AuditEvent;
import com.builtbyjuls.arat.platform.audit.AuditEventWriter;
import com.builtbyjuls.arat.platform.audit.AuditMetadata;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Service
public class RequirementReplacementService {

    private final GroupMembershipAccess groupMembershipAccess;
    private final PlanRepository planRepository;
    private final AuditEventWriter auditEventWriter;
    private final ObjectMapper objectMapper;

    public RequirementReplacementService(
            GroupMembershipAccess groupMembershipAccess,
            PlanRepository planRepository,
            AuditEventWriter auditEventWriter,
            ObjectMapper objectMapper) {
        this.groupMembershipAccess = groupMembershipAccess;
        this.planRepository = planRepository;
        this.auditEventWriter = auditEventWriter;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public RequirementRepresentation replace(ReplaceRequirementsCommand command) {
        var groupId = planRepository.findGroupId(command.planId())
                .orElseThrow(() -> failure(RequirementReplacementException.Reason.PRIVATE_RESOURCE_NOT_FOUND));
        if (!groupMembershipAccess.lockGroup(groupId)) {
            throw failure(RequirementReplacementException.Reason.PRIVATE_RESOURCE_NOT_FOUND);
        }
        var plan = planRepository.lockPlan(command.planId());
        if (!groupMembershipAccess.hasActiveOrganizer(groupId, command.actorId())) {
            if (groupMembershipAccess.hasActiveMembership(groupId, command.actorId())) {
                throw failure(RequirementReplacementException.Reason.FORBIDDEN_ROLE);
            }
            throw failure(RequirementReplacementException.Reason.PRIVATE_RESOURCE_NOT_FOUND);
        }
        if (plan.version() != command.expectedPlanVersion()) {
            throw failure(RequirementReplacementException.Reason.PRECONDITION_FAILED);
        }
        if (plan.state() != PlanState.COLLABORATING && plan.state() != PlanState.OPEN_FOR_OFFERS) {
            throw failure(RequirementReplacementException.Reason.INVALID_PLAN_STATE);
        }

        var retainedIds = retainedWindowIds(command);
        if (!planRepository.hasActiveCandidateWindows(command.planId(), retainedIds)) {
            throw failure(RequirementReplacementException.Reason.VALIDATION_FAILED);
        }
        var request = command.request();
        var draft = new RequirementDraft(
                command.planId(),
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
        var windows = candidateWindows(command);
        var updatedPlan = planRepository.replaceRequirementDraftVersioned(
                        command.planId(), command.expectedPlanVersion(), request.title(), draft)
                .orElseThrow(() -> failure(RequirementReplacementException.Reason.PRECONDITION_FAILED));
        planRepository.reconcileCandidateWindows(command.planId(), windows);
        planRepository.replaceMustHaves(command.planId(), request.mustHaves());
        auditEventWriter.append(new AuditEvent(
                UUID.randomUUID(),
                command.actorId(),
                "plan.requirements.replaced",
                "plan",
                command.planId(),
                groupId,
                command.planId(),
                command.correlationId(),
                AuditMetadata.empty()));
        var privatePlan = planRepository.findPrivate(command.planId(), groupId)
                .orElseThrow(() -> new IllegalStateException("plan disappeared during requirement replacement"));
        if (privatePlan.plan().version() != updatedPlan.version()) {
            throw new IllegalStateException("plan version changed during requirement replacement");
        }
        return RequirementRepresentation.from(privatePlan, objectMapper);
    }

    private Set<UUID> retainedWindowIds(ReplaceRequirementsCommand command) {
        return command.request().candidateWindows().stream()
                .map(window -> window.id())
                .filter(java.util.Objects::nonNull)
                .collect(java.util.stream.Collectors.toSet());
    }

    private List<CandidateWindow> candidateWindows(ReplaceRequirementsCommand command) {
        var windows = command.request().candidateWindows();
        return java.util.stream.IntStream.range(0, windows.size())
                .mapToObj(index -> {
                    var window = windows.get(index);
                    return new CandidateWindow(
                            window.id() == null ? UUID.randomUUID() : window.id(),
                            command.planId(),
                            index + 1,
                            window.startAt(),
                            window.endAt(),
                            null);
                })
                .toList();
    }

    private String categoryAttributesJson(com.builtbyjuls.arat.planning.api.RequirementReplacementRequest request) {
        try {
            return objectMapper.writeValueAsString(request.categoryAttributes());
        } catch (JacksonException exception) {
            throw new IllegalArgumentException("categoryAttributes cannot be serialized", exception);
        }
    }

    private RequirementReplacementException failure(RequirementReplacementException.Reason reason) {
        return new RequirementReplacementException(reason);
    }
}

package com.builtbyjuls.arat.planning.application;

import com.builtbyjuls.arat.groups.api.GroupMembershipAccess;
import com.builtbyjuls.arat.planning.api.CreatePreferenceCommand;
import com.builtbyjuls.arat.planning.api.PreferenceCollectionRepresentation;
import com.builtbyjuls.arat.planning.api.PreferenceException;
import com.builtbyjuls.arat.planning.api.PreferenceRepresentation;
import com.builtbyjuls.arat.planning.api.ReplacePreferenceCommand;
import com.builtbyjuls.arat.planning.domain.PlanPreference;
import com.builtbyjuls.arat.planning.domain.PlanState;
import com.builtbyjuls.arat.planning.infrastructure.PlanPreferenceRepository;
import com.builtbyjuls.arat.planning.infrastructure.PlanRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PlanPreferenceService {

    private final GroupMembershipAccess groupMembershipAccess;
    private final PlanRepository planRepository;
    private final PlanPreferenceRepository preferenceRepository;

    public PlanPreferenceService(
            GroupMembershipAccess groupMembershipAccess,
            PlanRepository planRepository,
            PlanPreferenceRepository preferenceRepository) {
        this.groupMembershipAccess = groupMembershipAccess;
        this.planRepository = planRepository;
        this.preferenceRepository = preferenceRepository;
    }

    @Transactional
    public PreferenceRepresentation create(CreatePreferenceCommand command) {
        if (command.request().basisPlanVersion() == null || command.request().basisPlanVersion() < 1) {
            throw failure(PreferenceException.Reason.VALIDATION_FAILED);
        }
        var groupId = planRepository.findGroupId(command.planId())
                .orElseThrow(() -> failure(PreferenceException.Reason.PRIVATE_RESOURCE_NOT_FOUND));
        requireActiveMembership(groupId, command.actorId());
        var plan = planRepository.lockPlan(command.planId());
        if (plan.state() != PlanState.COLLABORATING) {
            throw failure(PreferenceException.Reason.INVALID_PLAN_STATE);
        }
        if (plan.version() != command.request().basisPlanVersion()) {
            throw failure(PreferenceException.Reason.REQUIREMENT_VERSION_CHANGED);
        }
        var selectedWindowIds = new java.util.LinkedHashSet<>(command.request().selectedWindowIds());
        if (!planRepository.hasActiveCandidateWindows(command.planId(), selectedWindowIds)) {
            throw failure(PreferenceException.Reason.VALIDATION_FAILED);
        }
        var preference = new PlanPreference(
                command.planId(), command.actorId(), command.request().basisPlanVersion(), command.request().attendance(),
                command.request().guestCount(),
                command.request().personalBudget() == null ? null : command.request().personalBudget().minorUnits(),
                command.request().selectedWindowIds(), command.request().rankedPreferences(), command.request().privateNote(),
                1, null, null);
        var inserted = preferenceRepository.insert(preference)
                .orElseThrow(() -> failure(PreferenceException.Reason.PRECONDITION_FAILED));
        return PreferenceRepresentation.from(inserted);
    }

    @Transactional
    public PreferenceRepresentation replace(ReplacePreferenceCommand command) {
        if (command.request().basisPlanVersion() == null || command.request().basisPlanVersion() < 1) {
            throw failure(PreferenceException.Reason.VALIDATION_FAILED);
        }
        var groupId = planRepository.findGroupId(command.planId())
                .orElseThrow(() -> failure(PreferenceException.Reason.PRIVATE_RESOURCE_NOT_FOUND));
        requireActiveMembership(groupId, command.actorId());
        var plan = planRepository.lockPlan(command.planId());
        var existing = preferenceRepository.lock(command.planId(), command.actorId())
                .orElseThrow(() -> failure(PreferenceException.Reason.PREFERENCE_NOT_FOUND));
        if (plan.state() != PlanState.COLLABORATING) {
            throw failure(PreferenceException.Reason.INVALID_PLAN_STATE);
        }
        if (existing.version() != command.expectedPreferenceVersion()) {
            throw failure(PreferenceException.Reason.PRECONDITION_FAILED);
        }
        if (plan.version() != command.request().basisPlanVersion()) {
            throw failure(PreferenceException.Reason.REQUIREMENT_VERSION_CHANGED);
        }
        var selectedWindowIds = new java.util.LinkedHashSet<>(command.request().selectedWindowIds());
        if (!planRepository.hasActiveCandidateWindows(command.planId(), selectedWindowIds)) {
            throw failure(PreferenceException.Reason.VALIDATION_FAILED);
        }
        var replacement = new PlanPreference(
                command.planId(), command.actorId(), command.request().basisPlanVersion(), command.request().attendance(),
                command.request().guestCount(),
                command.request().personalBudget() == null ? null : command.request().personalBudget().minorUnits(),
                command.request().selectedWindowIds(), command.request().rankedPreferences(), command.request().privateNote(),
                existing.version(), existing.createdAt(), null);
        return PreferenceRepresentation.from(preferenceRepository.replace(replacement)
                .orElseThrow(() -> failure(PreferenceException.Reason.PRECONDITION_FAILED)));
    }

    @Transactional
    public PreferenceRepresentation findOwn(UUID planId, UUID actorId) {
        var plan = visiblePlan(planId, actorId);
        var preference = preferenceRepository.find(planId, actorId)
                .orElseThrow(() -> failure(PreferenceException.Reason.PREFERENCE_NOT_FOUND));
        return PreferenceRepresentation.from(preference);
    }

    @Transactional
    public PreferenceCollectionRepresentation findAll(UUID planId, UUID actorId) {
        var plan = visiblePlan(planId, actorId);
        List<com.builtbyjuls.arat.planning.api.GroupPreferenceRepresentation> preferences = preferenceRepository.findAllByPlanId(planId).stream()
                .map(preference -> com.builtbyjuls.arat.planning.api.GroupPreferenceRepresentation.from(preference, plan.version()))
                .toList();
        return new PreferenceCollectionRepresentation(preferences);
    }

    private com.builtbyjuls.arat.planning.domain.Plan visiblePlan(UUID planId, UUID actorId) {
        var groupId = planRepository.findGroupId(planId)
                .orElseThrow(() -> failure(PreferenceException.Reason.PRIVATE_RESOURCE_NOT_FOUND));
        requireActiveMembership(groupId, actorId);
        return planRepository.lockPlan(planId);
    }

    private void requireActiveMembership(UUID groupId, UUID actorId) {
        if (!groupMembershipAccess.lockAndHasActiveMembership(groupId, actorId)) {
            throw failure(PreferenceException.Reason.PRIVATE_RESOURCE_NOT_FOUND);
        }
    }

    private PreferenceException failure(PreferenceException.Reason reason) {
        return new PreferenceException(reason);
    }
}

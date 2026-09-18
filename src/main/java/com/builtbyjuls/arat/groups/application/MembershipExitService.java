package com.builtbyjuls.arat.groups.application;

import com.builtbyjuls.arat.groups.api.LeaveGroupCommand;
import com.builtbyjuls.arat.groups.api.MembershipExitException;
import com.builtbyjuls.arat.groups.api.RemoveGroupMemberCommand;
import com.builtbyjuls.arat.groups.domain.Membership;
import com.builtbyjuls.arat.groups.domain.MembershipRole;
import com.builtbyjuls.arat.groups.domain.MembershipStatus;
import com.builtbyjuls.arat.groups.infrastructure.GroupRepository;
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
import tools.jackson.databind.ObjectMapper;

@Service
public class MembershipExitService {

    public static final String LEAVE_OPERATION = "groups.members.leave";
    public static final String REMOVE_OPERATION = "groups.members.remove";

    private final GroupRepository groupRepository;
    private final IdempotencyRepository idempotencyRepository;
    private final AuditEventWriter auditEventWriter;
    private final ObjectMapper objectMapper;

    public MembershipExitService(
            GroupRepository groupRepository,
            IdempotencyRepository idempotencyRepository,
            AuditEventWriter auditEventWriter,
            ObjectMapper objectMapper) {
        this.groupRepository = groupRepository;
        this.idempotencyRepository = idempotencyRepository;
        this.auditEventWriter = auditEventWriter;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public String leave(LeaveGroupCommand command) {
        return exit(
                command.actorId(), command.groupId(), command.actorId(), command.idempotencyKey(),
                command.correlationId(), LEAVE_OPERATION, MembershipStatus.LEFT, "group.membership.left", false);
    }

    @Transactional
    public String remove(RemoveGroupMemberCommand command) {
        return exit(
                command.actorId(), command.groupId(), command.targetAccountId(), command.idempotencyKey(),
                command.correlationId(), REMOVE_OPERATION, MembershipStatus.REMOVED, "group.membership.removed", true);
    }

    private String exit(
            UUID actorId,
            UUID groupId,
            UUID targetAccountId,
            String idempotencyKey,
            String correlationId,
            String operation,
            MembershipStatus exitStatus,
            String auditAction,
            boolean requiresOrganizer) {
        var scope = new IdempotencyScope(actorId, operation, idempotencyKey);
        var fingerprint = RequestFingerprint.fromCanonicalFields(Map.of(
                "groupId", groupId,
                "targetAccountId", targetAccountId));
        var claim = idempotencyRepository.claim(scope, fingerprint);
        if (claim instanceof ClaimResult.Replay replay) {
            return replayEtag(replay.response());
        }
        rejectUnusableClaim(claim);

        groupRepository.findAndLockGroup(groupId)
                .orElseThrow(() -> new MembershipExitException(MembershipExitException.Reason.PRIVATE_RESOURCE_NOT_FOUND));
        var activeMemberships = groupRepository.findActiveMemberships(groupId);
        var actorMembership = activeMemberships.stream()
                .filter(membership -> membership.accountId().equals(actorId))
                .findFirst()
                .orElseThrow(() -> new MembershipExitException(MembershipExitException.Reason.PRIVATE_RESOURCE_NOT_FOUND));
        if (requiresOrganizer && actorId.equals(targetAccountId)) {
            throw new MembershipExitException(MembershipExitException.Reason.VALIDATION_FAILED);
        }
        if (requiresOrganizer && actorMembership.role() != MembershipRole.ORGANIZER) {
            throw new MembershipExitException(MembershipExitException.Reason.FORBIDDEN_ROLE);
        }
        var targetMembership = activeMemberships.stream()
                .filter(membership -> membership.accountId().equals(targetAccountId))
                .findFirst()
                .orElseThrow(() -> new MembershipExitException(MembershipExitException.Reason.PRIVATE_RESOURCE_NOT_FOUND));
        if (targetMembership.role() == MembershipRole.ORGANIZER && activeOrganizerCount(activeMemberships) == 1) {
            throw new MembershipExitException(MembershipExitException.Reason.FINAL_ORGANIZER_REQUIRED);
        }
        groupRepository.endActiveMembership(groupId, targetAccountId, exitStatus)
                .orElseThrow(() -> new IllegalStateException("active membership disappeared under group lock"));
        var group = groupRepository.incrementVersion(groupId);
        auditEventWriter.append(new AuditEvent(
                UUID.randomUUID(),
                actorId,
                auditAction,
                "groupMembership",
                targetAccountId,
                groupId,
                null,
                correlationId,
                AuditMetadata.references(Map.of("targetAccountId", targetAccountId))));
        var etag = GroupCreationService.etag(group.version());
        idempotencyRepository.complete(scope, new CompletedIdempotencyResponse(
                204,
                ReplayState.from(objectMapper.createObjectNode(), objectMapper),
                StoredReplayHeaders.from(Map.of("ETag", etag)),
                targetAccountId));
        return etag;
    }

    private long activeOrganizerCount(List<Membership> memberships) {
        return memberships.stream()
                .filter(membership -> membership.role() == MembershipRole.ORGANIZER)
                .count();
    }

    private String replayEtag(CompletedIdempotencyResponse response) {
        if (response.status() != 204) {
            throw new IllegalStateException("stored membership exit response is invalid");
        }
        var etag = response.headers().values().get("ETag");
        if (etag == null) {
            throw new IllegalStateException("stored membership exit response lacks ETag");
        }
        return etag;
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

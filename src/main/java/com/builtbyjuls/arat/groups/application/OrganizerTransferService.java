package com.builtbyjuls.arat.groups.application;

import com.builtbyjuls.arat.groups.api.OrganizerTransferException;
import com.builtbyjuls.arat.groups.api.OrganizerTransferRepresentation;
import com.builtbyjuls.arat.groups.api.TransferOrganizerCommand;
import com.builtbyjuls.arat.groups.domain.MembershipRole;
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
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Service
public class OrganizerTransferService {

    public static final String TRANSFER_ORGANIZER_OPERATION = "groups.organizer.transfer";

    private final GroupRepository groupRepository;
    private final IdempotencyRepository idempotencyRepository;
    private final AuditEventWriter auditEventWriter;
    private final ObjectMapper objectMapper;

    public OrganizerTransferService(
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
    public OrganizerTransferRepresentation transfer(TransferOrganizerCommand command) {
        var scope = new IdempotencyScope(
                command.actorId(), TRANSFER_ORGANIZER_OPERATION, command.idempotencyKey());
        var fingerprint = RequestFingerprint.fromCanonicalFields(Map.of(
                "groupId", command.groupId(),
                "targetAccountId", command.targetAccountId()));
        var claim = idempotencyRepository.claim(scope, fingerprint);
        if (claim instanceof ClaimResult.Replay replay) {
            return replayResponse(replay.response());
        }
        rejectUnusableClaim(claim);

        var group = groupRepository.findAndLockGroup(command.groupId())
                .orElseThrow(() -> new OrganizerTransferException(
                        OrganizerTransferException.Reason.PRIVATE_RESOURCE_NOT_FOUND));
        var activeMemberships = groupRepository.findActiveMemberships(command.groupId());
        var actorMembership = activeMemberships.stream()
                .filter(membership -> membership.accountId().equals(command.actorId()))
                .findFirst()
                .orElseThrow(() -> new OrganizerTransferException(
                        OrganizerTransferException.Reason.PRIVATE_RESOURCE_NOT_FOUND));
        if (group.version() != command.expectedGroupVersion()) {
            throw new OrganizerTransferException(OrganizerTransferException.Reason.PRECONDITION_FAILED);
        }
        if (actorMembership.role() != MembershipRole.ORGANIZER) {
            throw new OrganizerTransferException(OrganizerTransferException.Reason.FORBIDDEN_ROLE);
        }
        var targetMembership = activeMemberships.stream()
                .filter(membership -> membership.accountId().equals(command.targetAccountId()))
                .findFirst()
                .orElseThrow(() -> new OrganizerTransferException(
                        OrganizerTransferException.Reason.PRIVATE_RESOURCE_NOT_FOUND));
        if (command.actorId().equals(command.targetAccountId())) {
            throw new OrganizerTransferException(OrganizerTransferException.Reason.VALIDATION_FAILED);
        }
        if (targetMembership.role() == MembershipRole.ORGANIZER) {
            throw new OrganizerTransferException(OrganizerTransferException.Reason.ALREADY_ORGANIZER);
        }

        groupRepository.updateActiveMembershipRole(
                command.groupId(), command.targetAccountId(), MembershipRole.ORGANIZER);
        groupRepository.updateActiveMembershipRole(
                command.groupId(), command.actorId(), MembershipRole.MEMBER);
        var updatedGroup = groupRepository.incrementVersion(command.groupId());
        var representation = new OrganizerTransferRepresentation(
                command.groupId(), command.actorId(), command.targetAccountId(), updatedGroup.version());
        auditEventWriter.append(new AuditEvent(
                UUID.randomUUID(),
                command.actorId(),
                "group.organizer.transferred",
                "groupMembership",
                command.targetAccountId(),
                command.groupId(),
                null,
                command.correlationId(),
                AuditMetadata.references(Map.of("targetAccountId", command.targetAccountId()))));
        idempotencyRepository.complete(scope, new CompletedIdempotencyResponse(
                200,
                ReplayState.from(objectMapper.valueToTree(representation), objectMapper),
                StoredReplayHeaders.from(Map.of("ETag", GroupCreationService.etag(updatedGroup.version()))),
                command.groupId()));
        return representation;
    }

    private OrganizerTransferRepresentation replayResponse(CompletedIdempotencyResponse response) {
        if (response.status() != 200) {
            throw new IllegalStateException("stored organizer transfer response is invalid");
        }
        try {
            return objectMapper.treeToValue(response.replayState().value(), OrganizerTransferRepresentation.class);
        } catch (JacksonException exception) {
            throw new IllegalStateException("stored organizer transfer replay response is invalid", exception);
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

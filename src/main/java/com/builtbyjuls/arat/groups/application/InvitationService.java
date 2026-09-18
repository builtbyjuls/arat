package com.builtbyjuls.arat.groups.application;

import com.builtbyjuls.arat.groups.api.CreateInvitationCommand;
import com.builtbyjuls.arat.groups.api.AcceptInvitationCommand;
import com.builtbyjuls.arat.groups.api.GroupMembershipRepresentation;
import com.builtbyjuls.arat.groups.api.InvitationRepresentation;
import com.builtbyjuls.arat.groups.api.InvitationException;
import com.builtbyjuls.arat.groups.api.RevokeInvitationCommand;
import com.builtbyjuls.arat.groups.domain.MembershipRole;
import com.builtbyjuls.arat.groups.infrastructure.GroupRepository;
import com.builtbyjuls.arat.groups.infrastructure.InvitationRepository;
import com.builtbyjuls.arat.groups.infrastructure.InvitationTokenService;
import com.builtbyjuls.arat.identity.api.AccountDirectory;
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
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Service
public class InvitationService {

    public static final String CREATE_INVITATION_OPERATION = "groups.invites.create";
    public static final String REVOKE_INVITATION_OPERATION = "groups.invites.revoke";
    public static final String ACCEPT_INVITATION_OPERATION = "groups.invites.accept";
    private static final int DEFAULT_EXPIRY_HOURS = 72;

    private final GroupRepository groupRepository;
    private final InvitationRepository invitationRepository;
    private final InvitationTokenService invitationTokenService;
    private final AccountDirectory accountDirectory;
    private final IdempotencyRepository idempotencyRepository;
    private final AuditEventWriter auditEventWriter;
    private final ObjectMapper objectMapper;

    public InvitationService(
            GroupRepository groupRepository,
            InvitationRepository invitationRepository,
            InvitationTokenService invitationTokenService,
            AccountDirectory accountDirectory,
            IdempotencyRepository idempotencyRepository,
            AuditEventWriter auditEventWriter,
            ObjectMapper objectMapper) {
        this.groupRepository = groupRepository;
        this.invitationRepository = invitationRepository;
        this.invitationTokenService = invitationTokenService;
        this.accountDirectory = accountDirectory;
        this.idempotencyRepository = idempotencyRepository;
        this.auditEventWriter = auditEventWriter;
        this.objectMapper = objectMapper;
    }

    public static int defaultExpiryHours() {
        return DEFAULT_EXPIRY_HOURS;
    }

    @Transactional
    public InvitationRepresentation create(CreateInvitationCommand command) {
        var scope = new IdempotencyScope(command.actorId(), CREATE_INVITATION_OPERATION, command.idempotencyKey());
        var fingerprint = RequestFingerprint.fromCanonicalFields(Map.of(
                "expiryHours", command.expiryHours(),
                "groupId", command.groupId(),
                "inviteeAccountId", command.inviteeAccountId()));
        var claim = idempotencyRepository.claim(scope, fingerprint);
        if (claim instanceof ClaimResult.Replay replay) {
            return replayCreate(replay.response());
        }
        rejectUnusableClaim(claim);

        lockAndRequireOrganizer(command.groupId(), command.actorId());
        if (!accountDirectory.findByIds(java.util.List.of(command.inviteeAccountId())).containsKey(command.inviteeAccountId())) {
            throw new InvitationException(InvitationException.Reason.VALIDATION_FAILED);
        }
        if (groupRepository.findActiveMembership(command.groupId(), command.inviteeAccountId()).isPresent()) {
            throw new InvitationException(InvitationException.Reason.ALREADY_MEMBER);
        }
        invitationRepository.expirePending(command.groupId(), command.inviteeAccountId());

        var inviteId = UUID.randomUUID();
        var nonce = invitationTokenService.newNonce();
        var keyId = invitationTokenService.activeKeyId();
        var token = invitationTokenService.tokenFor(
                inviteId, command.groupId(), command.inviteeAccountId(), nonce, keyId);
        try {
            var invitation = invitationRepository.insert(
                    inviteId,
                    command.groupId(),
                    command.inviteeAccountId(),
                    keyId,
                    nonce,
                    invitationTokenService.digest(token),
                    command.expiryHours(),
                    command.actorId());
            auditEventWriter.append(new AuditEvent(
                    UUID.randomUUID(),
                    command.actorId(),
                    "group.invitation.created",
                    "groupInvitation",
                    invitation.inviteId(),
                    invitation.groupId(),
                    null,
                    command.correlationId(),
                    AuditMetadata.references(Map.of(
                            "inviteId", invitation.inviteId(),
                            "inviteeAccountId", invitation.inviteeAccountId()))));
            var replay = new InvitationReplayMetadata(
                    invitation.inviteId(), invitation.groupId(), invitation.inviteeAccountId(), invitation.expiresAt());
            idempotencyRepository.complete(scope, new CompletedIdempotencyResponse(
                    201,
                    ReplayState.from(objectMapper.valueToTree(replay), objectMapper),
                    StoredReplayHeaders.from(Map.of("Location", location(invitation.groupId(), invitation.inviteId()))),
                    invitation.inviteId()));
            return new InvitationRepresentation(
                    invitation.inviteId(), invitation.groupId(), invitation.inviteeAccountId(), invitation.expiresAt(), token);
        } catch (DuplicateKeyException exception) {
            throw new InvitationException(InvitationException.Reason.INVITATION_ALREADY_PENDING);
        }
    }

    @Transactional
    public void revoke(RevokeInvitationCommand command) {
        var scope = new IdempotencyScope(command.actorId(), REVOKE_INVITATION_OPERATION, command.idempotencyKey());
        var fingerprint = RequestFingerprint.fromCanonicalFields(Map.of(
                "groupId", command.groupId(),
                "inviteId", command.inviteId()));
        var claim = idempotencyRepository.claim(scope, fingerprint);
        if (claim instanceof ClaimResult.Replay) {
            return;
        }
        rejectUnusableClaim(claim);

        lockAndRequireOrganizer(command.groupId(), command.actorId());
        if (invitationRepository.revokePending(command.groupId(), command.inviteId()).isEmpty()) {
            throw new InvitationException(InvitationException.Reason.INVITATION_UNAVAILABLE);
        }
        auditEventWriter.append(new AuditEvent(
                UUID.randomUUID(),
                command.actorId(),
                "group.invitation.revoked",
                "groupInvitation",
                command.inviteId(),
                command.groupId(),
                null,
                command.correlationId(),
                AuditMetadata.references(Map.of("inviteId", command.inviteId()))));
        idempotencyRepository.complete(scope, new CompletedIdempotencyResponse(
                204,
                ReplayState.from(objectMapper.createObjectNode(), objectMapper),
                StoredReplayHeaders.from(Map.of()),
                command.inviteId()));
    }

    @Transactional
    public GroupMembershipRepresentation accept(AcceptInvitationCommand command) {
        var tokenDigest = invitationDigest(command.token());
        var scope = new IdempotencyScope(command.actorId(), ACCEPT_INVITATION_OPERATION, command.idempotencyKey());
        var fingerprint = RequestFingerprint.fromCanonicalFields(Map.of("tokenDigest", tokenDigest));
        var claim = idempotencyRepository.claim(scope, fingerprint);
        if (claim instanceof ClaimResult.Replay replay) {
            return replayAcceptance(replay.response());
        }
        rejectUnusableClaim(claim);

        var invitation = invitationRepository.findByTokenDigest(tokenDigest)
                .orElseThrow(() -> new InvitationException(InvitationException.Reason.INVITATION_UNAVAILABLE));
        groupRepository.lockGroup(invitation.groupId());
        invitation = invitationRepository.findByTokenDigest(tokenDigest)
                .orElseThrow(() -> new InvitationException(InvitationException.Reason.INVITATION_UNAVAILABLE));
        if (!invitation.inviteeAccountId().equals(command.actorId())) {
            throw new InvitationException(InvitationException.Reason.INVITATION_UNAVAILABLE);
        }
        if (invitationRepository.consumePending(invitation.inviteId()).isEmpty()) {
            throw new InvitationException(InvitationException.Reason.INVITATION_UNAVAILABLE);
        }
        if (groupRepository.activateMember(invitation.groupId(), command.actorId()).isEmpty()) {
            throw new InvitationException(InvitationException.Reason.INVITATION_UNAVAILABLE);
        }
        var group = groupRepository.incrementVersion(invitation.groupId());
        var representation = new GroupMembershipRepresentation(
                invitation.groupId(), command.actorId(), MembershipRole.MEMBER.name(), group.version());
        auditEventWriter.append(new AuditEvent(
                UUID.randomUUID(),
                command.actorId(),
                "group.invitation.accepted",
                "groupInvitation",
                invitation.inviteId(),
                invitation.groupId(),
                null,
                command.correlationId(),
                AuditMetadata.references(Map.of(
                        "inviteId", invitation.inviteId(),
                        "inviteeAccountId", invitation.inviteeAccountId()))));
        idempotencyRepository.complete(scope, new CompletedIdempotencyResponse(
                200,
                ReplayState.from(objectMapper.valueToTree(representation), objectMapper),
                StoredReplayHeaders.from(Map.of("ETag", GroupCreationService.etag(group.version()))),
                invitation.groupId()));
        return representation;
    }

    public static String location(UUID groupId, UUID inviteId) {
        return "/api/v1/groups/" + groupId + "/invites/" + inviteId;
    }

    private InvitationRepresentation replayCreate(CompletedIdempotencyResponse response) {
        var metadata = readReplayMetadata(response);
        var invitation = invitationRepository.findById(metadata.inviteId())
                .orElseThrow(() -> new IllegalStateException("replayed invitation is unavailable"));
        if (!invitation.groupId().equals(metadata.groupId())
                || !invitation.inviteeAccountId().equals(metadata.inviteeAccountId())
                || !invitation.expiresAt().equals(metadata.expiresAt())) {
            throw new IllegalStateException("replayed invitation metadata is invalid");
        }
        return new InvitationRepresentation(
                invitation.inviteId(), invitation.groupId(), invitation.inviteeAccountId(), invitation.expiresAt(),
                invitationTokenService.tokenFor(
                        invitation.inviteId(), invitation.groupId(), invitation.inviteeAccountId(),
                        invitation.nonce(), invitation.tokenKeyId()));
    }

    private GroupMembershipRepresentation replayAcceptance(CompletedIdempotencyResponse response) {
        try {
            return objectMapper.treeToValue(response.replayState().value(), GroupMembershipRepresentation.class);
        } catch (JacksonException exception) {
            throw new IllegalStateException("stored invitation acceptance replay response is invalid", exception);
        }
    }

    private String invitationDigest(String token) {
        try {
            return invitationTokenService.digest(token);
        } catch (IllegalArgumentException exception) {
            throw new InvitationException(InvitationException.Reason.INVITATION_UNAVAILABLE);
        }
    }

    private InvitationReplayMetadata readReplayMetadata(CompletedIdempotencyResponse response) {
        try {
            return objectMapper.treeToValue(response.replayState().value(), InvitationReplayMetadata.class);
        } catch (JacksonException exception) {
            throw new IllegalStateException("stored invitation replay response is invalid", exception);
        }
    }

    private void lockAndRequireOrganizer(UUID groupId, UUID actorId) {
        if (groupRepository.findById(groupId).isEmpty()) {
            throw new InvitationException(InvitationException.Reason.PRIVATE_RESOURCE_NOT_FOUND);
        }
        groupRepository.lockGroup(groupId);
        var membership = groupRepository.findActiveMembership(groupId, actorId);
        if (membership.isEmpty()) {
            throw new InvitationException(InvitationException.Reason.PRIVATE_RESOURCE_NOT_FOUND);
        }
        if (membership.orElseThrow().role() != MembershipRole.ORGANIZER) {
            throw new InvitationException(InvitationException.Reason.FORBIDDEN_ROLE);
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

    private record InvitationReplayMetadata(
            UUID inviteId,
            UUID groupId,
            UUID inviteeAccountId,
            OffsetDateTime expiresAt) {
    }
}

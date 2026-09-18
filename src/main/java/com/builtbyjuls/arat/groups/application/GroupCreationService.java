package com.builtbyjuls.arat.groups.application;

import com.builtbyjuls.arat.groups.api.CreateGroupCommand;
import com.builtbyjuls.arat.groups.api.GroupRepresentation;
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
public class GroupCreationService {

    public static final String CREATE_GROUP_OPERATION = "groups.create";

    private final GroupRepository groupRepository;
    private final IdempotencyRepository idempotencyRepository;
    private final AuditEventWriter auditEventWriter;
    private final ObjectMapper objectMapper;

    public GroupCreationService(
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
    public GroupRepresentation create(CreateGroupCommand command) {
        var scope = new IdempotencyScope(command.actorId(), CREATE_GROUP_OPERATION, command.idempotencyKey());
        var fingerprint = RequestFingerprint.fromCanonicalFields(Map.of(
                "description", command.description(),
                "name", command.name()));
        var claim = idempotencyRepository.claim(scope, fingerprint);
        if (claim instanceof ClaimResult.Replay replay) {
            return responseFrom(replay.response());
        }
        if (claim instanceof ClaimResult.ConflictingFingerprint) {
            throw new IdempotencyKeyReusedException();
        }
        if (claim instanceof ClaimResult.InProgress) {
            throw new IllegalStateException("idempotency command is still in progress");
        }

        var group = groupRepository.insertNew(
                UUID.randomUUID(), command.name(), command.description(), command.actorId());
        groupRepository.insertOrganizerMembership(group.groupId(), command.actorId());
        auditEventWriter.append(new AuditEvent(
                UUID.randomUUID(),
                command.actorId(),
                "group.created",
                "group",
                group.groupId(),
                group.groupId(),
                null,
                command.correlationId(),
                AuditMetadata.empty()));

        var representation = GroupRepresentation.from(group);
        idempotencyRepository.complete(scope, new CompletedIdempotencyResponse(
                201,
                ReplayState.from(objectMapper.valueToTree(representation), objectMapper),
                StoredReplayHeaders.from(Map.of(
                        "ETag", etag(group.version()),
                        "Location", location(group.groupId()))),
                group.groupId()));
        return representation;
    }

    public static String etag(long version) {
        return "\"" + version + "\"";
    }

    public static String location(UUID groupId) {
        return "/api/v1/groups/" + groupId;
    }

    private GroupRepresentation responseFrom(CompletedIdempotencyResponse response) {
        try {
            return objectMapper.treeToValue(response.replayState().value(), GroupRepresentation.class);
        } catch (JacksonException exception) {
            throw new IllegalStateException("stored group replay response is invalid", exception);
        }
    }
}

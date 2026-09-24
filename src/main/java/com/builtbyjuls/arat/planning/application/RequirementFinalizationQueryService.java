package com.builtbyjuls.arat.planning.application;

import com.builtbyjuls.arat.groups.api.GroupMembershipAccess;
import com.builtbyjuls.arat.planning.api.PlanQueryException;
import com.builtbyjuls.arat.planning.api.RequirementFinalizationPageRepresentation;
import com.builtbyjuls.arat.planning.api.RequirementFinalizationRepresentation;
import com.builtbyjuls.arat.planning.infrastructure.PlanRepository;
import com.builtbyjuls.arat.planning.infrastructure.RequirementFinalizationRepository;
import java.nio.charset.StandardCharsets;
import java.time.DateTimeException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Service
public class RequirementFinalizationQueryService {

    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 100;
    private static final OffsetDateTime POSTGRES_MINIMUM_TIMESTAMP = OffsetDateTime.of(-4712, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC);
    private static final OffsetDateTime POSTGRES_MAXIMUM_TIMESTAMP = OffsetDateTime.of(294276, 12, 31, 23, 59, 59, 999_999_000, ZoneOffset.UTC);

    private final GroupMembershipAccess groupMembershipAccess;
    private final PlanRepository planRepository;
    private final RequirementFinalizationRepository finalizationRepository;
    private final ObjectMapper objectMapper;

    public RequirementFinalizationQueryService(
            GroupMembershipAccess groupMembershipAccess,
            PlanRepository planRepository,
            RequirementFinalizationRepository finalizationRepository,
            ObjectMapper objectMapper) {
        this.groupMembershipAccess = groupMembershipAccess;
        this.planRepository = planRepository;
        this.finalizationRepository = finalizationRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public RequirementFinalizationPageRepresentation listHistory(
            UUID planId, UUID actorId, String cursor, Integer requestedLimit) {
        requireActiveMembership(planId, actorId);
        var limit = limit(requestedLimit);
        var decoded = cursor == null ? null : decode(cursor, actorId, planId);
        var planVersion = planRepository.findVersion(planId).orElseThrow(this::privateNotFound);
        var finalizations = decoded == null
                ? finalizationRepository.listPage(planId, limit + 1)
                : finalizationRepository.listPage(planId, decoded.toCreatedAt(), decoded.finalizationId(), limit + 1);
        var hasNextPage = finalizations.size() > limit;
        var items = finalizations.stream().limit(limit)
                .map(finalization -> RequirementFinalizationRepresentation.from(
                        finalization, objectMapper, finalization.basisPlanVersion() == planVersion))
                .toList();
        var nextCursor = hasNextPage
                ? encode(actorId, planId, finalizations.get(limit - 1).createdAt(), finalizations.get(limit - 1).finalizationId())
                : null;
        return new RequirementFinalizationPageRepresentation(items, nextCursor);
    }

    private void requireActiveMembership(UUID planId, UUID actorId) {
        var groupId = planRepository.findGroupId(planId).orElseThrow(this::privateNotFound);
        if (!groupMembershipAccess.lockAndHasActiveMembership(groupId, actorId)) {
            throw privateNotFound();
        }
    }

    private int limit(Integer requestedLimit) {
        if (requestedLimit == null) {
            return DEFAULT_LIMIT;
        }
        if (requestedLimit < 1 || requestedLimit > MAX_LIMIT) {
            throw invalidCursor();
        }
        return requestedLimit;
    }

    private String encode(UUID actorId, UUID planId, OffsetDateTime createdAt, UUID finalizationId) {
        try {
            return Base64.getUrlEncoder().withoutPadding().encodeToString(objectMapper.writeValueAsBytes(
                    new CursorPayload(1, actorId, planId, createdAt.withOffsetSameInstant(ZoneOffset.UTC).toString(), finalizationId)));
        } catch (JacksonException exception) {
            throw new IllegalStateException("requirement finalization cursor cannot be encoded", exception);
        }
    }

    private CursorPayload decode(String cursor, UUID actorId, UUID planId) {
        try {
            if (cursor.isBlank() || cursor.contains("=") || !cursor.matches("[A-Za-z0-9_-]+")) {
                throw new IllegalArgumentException();
            }
            var payload = objectMapper.readValue(
                    Base64.getUrlDecoder().decode(cursor.getBytes(StandardCharsets.US_ASCII)), CursorPayload.class);
            if (payload == null || payload.version() != 1 || !actorId.equals(payload.actorId())
                    || !planId.equals(payload.planId()) || payload.createdAt() == null || payload.finalizationId() == null) {
                throw new IllegalArgumentException();
            }
            var createdAt = OffsetDateTime.parse(payload.createdAt());
            var utcCreatedAt = createdAt.withOffsetSameInstant(ZoneOffset.UTC);
            if (createdAt.getNano() % 1_000 != 0 || utcCreatedAt.isBefore(POSTGRES_MINIMUM_TIMESTAMP)
                    || utcCreatedAt.isAfter(POSTGRES_MAXIMUM_TIMESTAMP)) {
                throw new IllegalArgumentException();
            }
            return new CursorPayload(payload.version(), payload.actorId(), payload.planId(), utcCreatedAt.toString(), payload.finalizationId());
        } catch (IllegalArgumentException | DateTimeException | JacksonException exception) {
            throw invalidCursor();
        }
    }

    private PlanQueryException privateNotFound() {
        return new PlanQueryException(PlanQueryException.Reason.PRIVATE_RESOURCE_NOT_FOUND);
    }

    private PlanQueryException invalidCursor() {
        return new PlanQueryException(PlanQueryException.Reason.INVALID_CURSOR);
    }

    private record CursorPayload(int version, UUID actorId, UUID planId, String createdAt, UUID finalizationId) {
        OffsetDateTime toCreatedAt() {
            return OffsetDateTime.parse(createdAt).withOffsetSameInstant(ZoneOffset.UTC);
        }
    }
}

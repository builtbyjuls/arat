package com.builtbyjuls.arat.planning.application;

import com.builtbyjuls.arat.groups.api.GroupMembershipAccess;
import com.builtbyjuls.arat.planning.api.PlanDetailRepresentation;
import com.builtbyjuls.arat.planning.api.PlanPageRepresentation;
import com.builtbyjuls.arat.planning.api.PlanQueryException;
import com.builtbyjuls.arat.planning.api.PlanSummaryRepresentation;
import com.builtbyjuls.arat.planning.infrastructure.PlanRepository;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.DateTimeException;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Service
public class PlanQueryService {

    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 100;
    private static final OffsetDateTime POSTGRES_MINIMUM_TIMESTAMP = OffsetDateTime.of(-4712, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC);
    private static final OffsetDateTime POSTGRES_MAXIMUM_TIMESTAMP = OffsetDateTime.of(294276, 12, 31, 23, 59, 59, 999_999_000, ZoneOffset.UTC);

    private final GroupMembershipAccess groupMembershipAccess;
    private final PlanRepository planRepository;
    private final ObjectMapper objectMapper;

    public PlanQueryService(GroupMembershipAccess groupMembershipAccess, PlanRepository planRepository, ObjectMapper objectMapper) {
        this.groupMembershipAccess = groupMembershipAccess;
        this.planRepository = planRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public PlanDetailRepresentation findPrivate(UUID planId, UUID actorId) {
        var groupId = planRepository.findGroupId(planId).orElseThrow(() -> new PlanQueryException(PlanQueryException.Reason.PRIVATE_RESOURCE_NOT_FOUND));
        requireActiveMembership(groupId, actorId);
        var privatePlan = planRepository.findPrivate(planId, groupId)
                .orElseThrow(() -> new PlanQueryException(PlanQueryException.Reason.PRIVATE_RESOURCE_NOT_FOUND));
        return PlanDetailRepresentation.from(privatePlan, objectMapper);
    }

    @Transactional
    public PlanPageRepresentation listPrivate(UUID groupId, UUID actorId, String cursor, Integer requestedLimit) {
        requireActiveMembership(groupId, actorId);
        var limit = limit(requestedLimit);
        var decoded = cursor == null ? null : decode(cursor);
        var plans = decoded == null
                ? planRepository.listPage(groupId, limit + 1)
                : planRepository.listPage(groupId, decoded.toCreatedAt(), decoded.planId(), limit + 1);
        var hasNextPage = plans.size() > limit;
        var items = plans.stream().limit(limit).map(PlanSummaryRepresentation::from).toList();
        var nextCursor = hasNextPage ? encode(plans.get(limit - 1).createdAt(), plans.get(limit - 1).planId()) : null;
        return new PlanPageRepresentation(items, nextCursor);
    }

    private void requireActiveMembership(UUID groupId, UUID actorId) {
        if (!groupMembershipAccess.lockAndHasActiveMembership(groupId, actorId)) {
            throw new PlanQueryException(PlanQueryException.Reason.PRIVATE_RESOURCE_NOT_FOUND);
        }
    }

    private int limit(Integer requestedLimit) {
        if (requestedLimit == null) {
            return DEFAULT_LIMIT;
        }
        if (requestedLimit < 1 || requestedLimit > MAX_LIMIT) {
            throw new PlanQueryException(PlanQueryException.Reason.INVALID_CURSOR);
        }
        return requestedLimit;
    }

    private String encode(OffsetDateTime createdAt, UUID planId) {
        try {
            var payload = objectMapper.writeValueAsBytes(new CursorPayload(1, createdAt.withOffsetSameInstant(ZoneOffset.UTC).toString(), planId));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(payload);
        } catch (JacksonException exception) {
            throw new IllegalStateException("plan cursor cannot be encoded", exception);
        }
    }

    private CursorPayload decode(String cursor) {
        try {
            if (cursor.isBlank() || cursor.contains("=") || !cursor.matches("[A-Za-z0-9_-]+")) {
                throw new IllegalArgumentException();
            }
            var payload = objectMapper.readValue(Base64.getUrlDecoder().decode(cursor), CursorPayload.class);
            if (payload == null || payload.version() != 1 || payload.createdAt() == null || payload.planId() == null) {
                throw new IllegalArgumentException();
            }
            var createdAt = OffsetDateTime.parse(payload.createdAt());
            var utcCreatedAt = createdAt.withOffsetSameInstant(ZoneOffset.UTC);
            if (createdAt.getNano() % 1_000 != 0
                    || utcCreatedAt.isBefore(POSTGRES_MINIMUM_TIMESTAMP)
                    || utcCreatedAt.isAfter(POSTGRES_MAXIMUM_TIMESTAMP)) {
                throw new IllegalArgumentException();
            }
            return payload;
        } catch (IllegalArgumentException | DateTimeException | JacksonException exception) {
            throw new PlanQueryException(PlanQueryException.Reason.INVALID_CURSOR);
        }
    }

    private record CursorPayload(int version, String createdAt, UUID planId) {
        OffsetDateTime toCreatedAt() {
            return OffsetDateTime.parse(createdAt).withOffsetSameInstant(ZoneOffset.UTC);
        }
    }
}

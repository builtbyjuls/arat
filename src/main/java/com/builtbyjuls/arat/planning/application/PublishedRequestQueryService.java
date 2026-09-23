package com.builtbyjuls.arat.planning.application;

import com.builtbyjuls.arat.groups.api.GroupMembershipAccess;
import com.builtbyjuls.arat.planning.api.GroupPublishedRequestRepresentation;
import com.builtbyjuls.arat.planning.api.PlanQueryException;
import com.builtbyjuls.arat.planning.api.ProviderSafeRequestSnapshot;
import com.builtbyjuls.arat.planning.api.PublishedRequestPageRepresentation;
import com.builtbyjuls.arat.planning.domain.PublishedRequest;
import com.builtbyjuls.arat.planning.infrastructure.PlanRepository;
import com.builtbyjuls.arat.planning.infrastructure.PublishedRequestRepository;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@Service
public class PublishedRequestQueryService {

    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 100;

    private final GroupMembershipAccess groupMembershipAccess;
    private final PlanRepository planRepository;
    private final PublishedRequestRepository requestRepository;
    private final ObjectMapper objectMapper;

    public PublishedRequestQueryService(GroupMembershipAccess groupMembershipAccess, PlanRepository planRepository,
            PublishedRequestRepository requestRepository, ObjectMapper objectMapper) {
        this.groupMembershipAccess = groupMembershipAccess;
        this.planRepository = planRepository;
        this.requestRepository = requestRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public GroupPublishedRequestRepresentation findCurrent(UUID planId, UUID actorId) {
        requireActiveMembership(planId, actorId);
        var observation = requestRepository.findCurrentObservationByPlanId(planId).orElseThrow(this::privateNotFound);
        return representation(observation.request(), observation.actionable());
    }

    @Transactional
    public GroupPublishedRequestRepresentation findHistoryItem(UUID planId, UUID requestId, UUID actorId) {
        requireActiveMembership(planId, actorId);
        var observation = requestRepository.findObservationByPlanIdAndRequestId(planId, requestId)
                .orElseThrow(this::privateNotFound);
        return representation(observation.request(), observation.actionable());
    }

    @Transactional
    public PublishedRequestPageRepresentation listHistory(UUID planId, UUID actorId, String cursor, Integer requestedLimit) {
        requireActiveMembership(planId, actorId);
        var limit = limit(requestedLimit);
        var decoded = cursor == null ? null : decode(cursor, planId);
        var observations = decoded == null ? requestRepository.listObservationsPage(planId, limit + 1)
                : requestRepository.listObservationsPage(planId, decoded.requestVersion(), limit + 1);
        var hasNextPage = observations.size() > limit;
        var items = observations.stream().limit(limit)
                .map(observation -> representation(observation.request(), observation.actionable())).toList();
        var nextCursor = hasNextPage ? encode(planId, observations.get(limit - 1).request().requestVersion()) : null;
        return new PublishedRequestPageRepresentation(items, nextCursor);
    }

    private void requireActiveMembership(UUID planId, UUID actorId) {
        var groupId = planRepository.findGroupId(planId).orElseThrow(this::privateNotFound);
        if (!groupMembershipAccess.lockAndHasActiveMembership(groupId, actorId)) {
            throw privateNotFound();
        }
    }

    private GroupPublishedRequestRepresentation representation(PublishedRequest request, boolean actionable) {
        Map<String, Object> attributes;
        try {
            attributes = objectMapper.readValue(request.categoryAttributes(), new TypeReference<>() {});
        } catch (JacksonException exception) {
            throw new IllegalStateException("stored category attributes are invalid", exception);
        }
        return GroupPublishedRequestRepresentation.from(new ProviderSafeRequestSnapshot(
                request.requestId(), request.requestVersion(), request.state().name(), request.distributionMode().name(),
                request.category().name(), request.timeZone(), request.areaCode(), request.radiusKm(),
                request.requestedStartsAt(), request.requestedEndsAt(), request.minimumHeadcount(), request.maximumHeadcount(),
                request.budgetMinimumMinorUnits(), request.budgetMaximumMinorUnits(), request.mustHaves(),
                request.providerSafeNotes(), attributes, request.offerDeadline(), request.publishedAt(), actionable), request.closedAt());
    }

    private int limit(Integer requestedLimit) {
        if (requestedLimit == null) return DEFAULT_LIMIT;
        if (requestedLimit < 1 || requestedLimit > MAX_LIMIT) throw invalidCursor();
        return requestedLimit;
    }

    private String encode(UUID planId, long requestVersion) {
        try {
            return Base64.getUrlEncoder().withoutPadding().encodeToString(
                    objectMapper.writeValueAsBytes(new CursorPayload(1, planId, requestVersion)));
        } catch (JacksonException exception) {
            throw new IllegalStateException("published request cursor cannot be encoded", exception);
        }
    }

    private CursorPayload decode(String cursor, UUID planId) {
        try {
            if (cursor.isBlank() || cursor.contains("=") || !cursor.matches("[A-Za-z0-9_-]+")) throw new IllegalArgumentException();
            var payload = objectMapper.readValue(Base64.getUrlDecoder().decode(cursor.getBytes(StandardCharsets.US_ASCII)), CursorPayload.class);
            if (payload == null || payload.version() != 1 || !planId.equals(payload.planId()) || payload.requestVersion() < 1) throw new IllegalArgumentException();
            return payload;
        } catch (IllegalArgumentException | JacksonException exception) {
            throw invalidCursor();
        }
    }

    private PlanQueryException privateNotFound() { return new PlanQueryException(PlanQueryException.Reason.PRIVATE_RESOURCE_NOT_FOUND); }
    private PlanQueryException invalidCursor() { return new PlanQueryException(PlanQueryException.Reason.INVALID_CURSOR); }
    private record CursorPayload(int version, UUID planId, long requestVersion) {}
}

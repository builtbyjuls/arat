package com.builtbyjuls.arat.marketplace.application;

import com.builtbyjuls.arat.marketplace.api.ProviderRequestFeedException;
import com.builtbyjuls.arat.marketplace.api.ProviderRequestFeedRepresentation;
import com.builtbyjuls.arat.marketplace.api.PublishedRequestRepresentation;
import com.builtbyjuls.arat.marketplace.infrastructure.RequestRecipientRepository;
import com.builtbyjuls.arat.planning.api.PlanningRequestAccess;
import com.builtbyjuls.arat.providers.api.ProviderEligibilityAccess;
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
public class ProviderRequestFeedService {

    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 100;
    private static final OffsetDateTime POSTGRES_MINIMUM_TIMESTAMP = OffsetDateTime.of(-4712, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC);
    private static final OffsetDateTime POSTGRES_MAXIMUM_TIMESTAMP = OffsetDateTime.of(294276, 12, 31, 23, 59, 59, 999_999_000, ZoneOffset.UTC);

    private final ProviderEligibilityAccess providerEligibilityAccess;
    private final RequestRecipientRepository recipientRepository;
    private final PlanningRequestAccess planningRequestAccess;
    private final ObjectMapper objectMapper;

    public ProviderRequestFeedService(
            ProviderEligibilityAccess providerEligibilityAccess,
            RequestRecipientRepository recipientRepository,
            PlanningRequestAccess planningRequestAccess,
            ObjectMapper objectMapper) {
        this.providerEligibilityAccess = providerEligibilityAccess;
        this.recipientRepository = recipientRepository;
        this.planningRequestAccess = planningRequestAccess;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public ProviderRequestFeedRepresentation list(UUID providerId, UUID actorId, String cursor, Integer requestedLimit) {
        var eligibilityVersion = providerEligibilityAccess
                .lockAndFindVerifiedEligibilityVersionForActiveStaff(providerId, actorId)
                .orElseThrow(this::privateNotFound);
        var limit = limit(requestedLimit);
        var decoded = cursor == null ? null : decode(cursor, providerId);
        var recipients = recipientRepository.findActiveProviderFeedPage(
                providerId,
                eligibilityVersion,
                decoded == null ? null : decoded.createdAt(),
                decoded == null ? null : decoded.requestId(),
                limit + 1);
        var hasNextPage = recipients.size() > limit;
        var page = recipients.stream().limit(limit).toList();
        var items = page.stream()
                .map(recipient -> planningRequestAccess.findProviderSafeSnapshot(recipient.publishedRequestId())
                        .orElseThrow(() -> new IllegalStateException("recipient request is missing")))
                .map(PublishedRequestRepresentation::from)
                .toList();
        var nextCursor = hasNextPage ? encode(providerId, page.getLast().createdAt(), page.getLast().publishedRequestId()) : null;
        return new ProviderRequestFeedRepresentation(items, nextCursor);
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

    private String encode(UUID providerId, OffsetDateTime createdAt, UUID requestId) {
        try {
            return Base64.getUrlEncoder().withoutPadding().encodeToString(
                    objectMapper.writeValueAsBytes(
                            new CursorPayload(1, providerId, createdAt.withOffsetSameInstant(ZoneOffset.UTC), requestId)));
        } catch (JacksonException exception) {
            throw new IllegalStateException("provider request feed cursor cannot be encoded", exception);
        }
    }

    private CursorPayload decode(String cursor, UUID providerId) {
        try {
            if (cursor.isBlank() || cursor.contains("=") || !cursor.matches("[A-Za-z0-9_-]+")) {
                throw new IllegalArgumentException();
            }
            var payload = objectMapper.readValue(
                    Base64.getUrlDecoder().decode(cursor.getBytes(StandardCharsets.US_ASCII)), CursorPayload.class);
            if (payload == null
                    || payload.version() != 1
                    || !providerId.equals(payload.providerId())
                    || payload.createdAt() == null
                    || payload.requestId() == null) {
                throw new IllegalArgumentException();
            }
            var utcCreatedAt = payload.createdAt().withOffsetSameInstant(ZoneOffset.UTC);
            if (payload.createdAt().getNano() % 1_000 != 0
                    || utcCreatedAt.isBefore(POSTGRES_MINIMUM_TIMESTAMP)
                    || utcCreatedAt.isAfter(POSTGRES_MAXIMUM_TIMESTAMP)) {
                throw new IllegalArgumentException();
            }
            return payload;
        } catch (IllegalArgumentException | DateTimeException | JacksonException exception) {
            throw invalidCursor();
        }
    }

    private ProviderRequestFeedException privateNotFound() {
        return new ProviderRequestFeedException(ProviderRequestFeedException.Reason.PRIVATE_RESOURCE_NOT_FOUND);
    }

    private ProviderRequestFeedException invalidCursor() {
        return new ProviderRequestFeedException(ProviderRequestFeedException.Reason.INVALID_CURSOR);
    }

    private record CursorPayload(int version, UUID providerId, OffsetDateTime createdAt, UUID requestId) {
    }
}

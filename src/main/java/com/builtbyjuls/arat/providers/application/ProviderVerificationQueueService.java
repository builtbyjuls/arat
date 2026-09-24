package com.builtbyjuls.arat.providers.application;

import com.builtbyjuls.arat.identity.api.PlatformRole;
import com.builtbyjuls.arat.providers.api.PendingProviderVerificationPageRepresentation;
import com.builtbyjuls.arat.providers.api.PendingProviderVerificationRepresentation;
import com.builtbyjuls.arat.providers.api.ProviderVerificationQueueException;
import com.builtbyjuls.arat.providers.infrastructure.ProviderRepository;
import java.nio.charset.StandardCharsets;
import java.time.DateTimeException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Service
public class ProviderVerificationQueueService {

    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 100;
    private static final OffsetDateTime POSTGRES_MINIMUM_TIMESTAMP =
            OffsetDateTime.of(-4712, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC);
    private static final OffsetDateTime POSTGRES_MAXIMUM_TIMESTAMP =
            OffsetDateTime.of(294276, 12, 31, 23, 59, 59, 999_999_000, ZoneOffset.UTC);

    private final ProviderRepository providerRepository;
    private final ObjectMapper objectMapper;

    public ProviderVerificationQueueService(ProviderRepository providerRepository, ObjectMapper objectMapper) {
        this.providerRepository = providerRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public PendingProviderVerificationPageRepresentation list(
            UUID actorId, Set<PlatformRole> platformRoles, String cursor, Integer requestedLimit) {
        if (!platformRoles.contains(PlatformRole.PLATFORM_OPERATOR)) {
            throw failure(ProviderVerificationQueueException.Reason.FORBIDDEN_PLATFORM_ROLE);
        }
        var limit = limit(requestedLimit);
        var decoded = cursor == null ? null : decode(cursor, actorId);
        var verifications = decoded == null
                ? providerRepository.listPendingVerificationPage(limit + 1)
                : providerRepository.listPendingVerificationPage(
                        decoded.toSubmittedAt(), decoded.submissionId(), limit + 1);
        var hasNextPage = verifications.size() > limit;
        var items = verifications.stream()
                .limit(limit)
                .map(PendingProviderVerificationRepresentation::from)
                .toList();
        var nextCursor = hasNextPage
                ? encode(actorId, verifications.get(limit - 1).submittedAt(), verifications.get(limit - 1).submissionId())
                : null;
        return new PendingProviderVerificationPageRepresentation(items, nextCursor);
    }

    private int limit(Integer requestedLimit) {
        if (requestedLimit == null) {
            return DEFAULT_LIMIT;
        }
        if (requestedLimit < 1 || requestedLimit > MAX_LIMIT) {
            throw failure(ProviderVerificationQueueException.Reason.INVALID_CURSOR);
        }
        return requestedLimit;
    }

    private String encode(UUID actorId, OffsetDateTime submittedAt, UUID submissionId) {
        try {
            return Base64.getUrlEncoder().withoutPadding().encodeToString(objectMapper.writeValueAsBytes(
                    new CursorPayload(
                            1,
                            actorId,
                            submittedAt.withOffsetSameInstant(ZoneOffset.UTC).toString(),
                            submissionId)));
        } catch (JacksonException exception) {
            throw new IllegalStateException("provider verification queue cursor cannot be encoded", exception);
        }
    }

    private CursorPayload decode(String cursor, UUID actorId) {
        try {
            if (cursor.isBlank() || cursor.contains("=") || !cursor.matches("[A-Za-z0-9_-]+")) {
                throw new IllegalArgumentException();
            }
            var payload = objectMapper.readValue(
                    Base64.getUrlDecoder().decode(cursor.getBytes(StandardCharsets.US_ASCII)), CursorPayload.class);
            if (payload == null
                    || payload.version() != 1
                    || !actorId.equals(payload.actorId())
                    || payload.submittedAt() == null
                    || payload.submissionId() == null) {
                throw new IllegalArgumentException();
            }
            var submittedAt = OffsetDateTime.parse(payload.submittedAt());
            var utcSubmittedAt = submittedAt.withOffsetSameInstant(ZoneOffset.UTC);
            if (submittedAt.getNano() % 1_000 != 0
                    || utcSubmittedAt.isBefore(POSTGRES_MINIMUM_TIMESTAMP)
                    || utcSubmittedAt.isAfter(POSTGRES_MAXIMUM_TIMESTAMP)) {
                throw new IllegalArgumentException();
            }
            return new CursorPayload(
                    payload.version(), payload.actorId(), utcSubmittedAt.toString(), payload.submissionId());
        } catch (IllegalArgumentException | DateTimeException | JacksonException exception) {
            throw failure(ProviderVerificationQueueException.Reason.INVALID_CURSOR);
        }
    }

    private ProviderVerificationQueueException failure(ProviderVerificationQueueException.Reason reason) {
        return new ProviderVerificationQueueException(reason);
    }

    private record CursorPayload(int version, UUID actorId, String submittedAt, UUID submissionId) {
        OffsetDateTime toSubmittedAt() {
            return OffsetDateTime.parse(submittedAt).withOffsetSameInstant(ZoneOffset.UTC);
        }
    }
}

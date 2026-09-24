package com.builtbyjuls.arat.providers.application;

import com.builtbyjuls.arat.platform.audit.AuditEvent;
import com.builtbyjuls.arat.platform.audit.AuditEventWriter;
import com.builtbyjuls.arat.platform.audit.AuditMetadata;
import com.builtbyjuls.arat.providers.api.ProviderDetailRepresentation;
import com.builtbyjuls.arat.providers.api.ProviderProfileException;
import com.builtbyjuls.arat.providers.api.ProviderIndexException;
import com.builtbyjuls.arat.providers.api.ProviderPageRepresentation;
import com.builtbyjuls.arat.providers.api.ProviderRepresentation;
import com.builtbyjuls.arat.providers.api.ProviderSummaryRepresentation;
import com.builtbyjuls.arat.providers.api.ReplaceProviderProfileCommand;
import com.builtbyjuls.arat.providers.domain.ProviderStaffRole;
import com.builtbyjuls.arat.providers.domain.ProviderStaffStatus;
import com.builtbyjuls.arat.providers.infrastructure.ProviderRepository;
import java.nio.charset.StandardCharsets;
import java.time.DateTimeException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Service
public class ProviderProfileService {

    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 100;
    private static final OffsetDateTime POSTGRES_MINIMUM_TIMESTAMP = OffsetDateTime.of(-4712, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC);
    private static final OffsetDateTime POSTGRES_MAXIMUM_TIMESTAMP = OffsetDateTime.of(294276, 12, 31, 23, 59, 59, 999_999_000, ZoneOffset.UTC);

    private final ProviderRepository providerRepository;
    private final AuditEventWriter auditEventWriter;
    private final ObjectMapper objectMapper;

    public ProviderProfileService(
            ProviderRepository providerRepository, AuditEventWriter auditEventWriter, ObjectMapper objectMapper) {
        this.providerRepository = providerRepository;
        this.auditEventWriter = auditEventWriter;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public ProviderDetailRepresentation read(UUID providerId, UUID actorId) {
        var organization = providerRepository.findAndLockOrganization(providerId)
                .orElseThrow(() -> failure(ProviderProfileException.Reason.PRIVATE_RESOURCE_NOT_FOUND));
        var membership = requireActiveStaff(providerId, actorId);
        return ProviderDetailRepresentation.from(
                organization,
                providerRepository.findSupportedCategories(providerId),
                providerRepository.findServiceAreaCodes(providerId),
                membership.role());
    }

    @Transactional(readOnly = true)
    public ProviderPageRepresentation listForActor(UUID actorId, String cursor, Integer requestedLimit) {
        var limit = limit(requestedLimit);
        var decoded = cursor == null ? null : decode(cursor, actorId);
        var providers = decoded == null
                ? providerRepository.listActiveStaffMembershipPage(actorId, limit + 1)
                : providerRepository.listActiveStaffMembershipPage(
                        actorId, decoded.toCreatedAt(), decoded.providerId(), limit + 1);
        var hasNextPage = providers.size() > limit;
        var items = providers.stream()
                .limit(limit)
                .map(ProviderSummaryRepresentation::from)
                .toList();
        var nextCursor = hasNextPage
                ? encode(actorId, providers.get(limit - 1).organization().createdAt(), providers.get(limit - 1).organization().providerId())
                : null;
        return new ProviderPageRepresentation(items, nextCursor);
    }

    @Transactional
    public ProviderRepresentation replace(ReplaceProviderProfileCommand command) {
        var organization = providerRepository.findAndLockOrganization(command.providerId())
                .orElseThrow(() -> failure(ProviderProfileException.Reason.PRIVATE_RESOURCE_NOT_FOUND));
        var membership = requireActiveStaff(command.providerId(), command.actorId());
        if (membership.role() != ProviderStaffRole.ADMIN) {
            throw failure(ProviderProfileException.Reason.FORBIDDEN_ROLE);
        }
        if (organization.version() != command.expectedVersion()) {
            throw failure(ProviderProfileException.Reason.PRECONDITION_FAILED);
        }

        var updated = providerRepository.replaceProfileVersioned(
                        command.providerId(),
                        command.expectedVersion(),
                        command.displayName(),
                        command.supportedCategories(),
                        command.serviceAreaCodes())
                .orElseThrow(() -> failure(ProviderProfileException.Reason.PRECONDITION_FAILED));
        auditEventWriter.append(new AuditEvent(
                UUID.randomUUID(),
                command.actorId(),
                "provider.profile.replaced",
                "provider",
                command.providerId(),
                null,
                null,
                command.correlationId(),
                AuditMetadata.references(Map.of("providerId", command.providerId()))));
        return ProviderRepresentation.from(updated, command.supportedCategories(), command.serviceAreaCodes());
    }

    private com.builtbyjuls.arat.providers.domain.ProviderStaffMembership requireActiveStaff(UUID providerId, UUID actorId) {
        return providerRepository.findStaffMembership(providerId, actorId)
                .filter(membership -> membership.status() == ProviderStaffStatus.ACTIVE)
                .orElseThrow(() -> failure(ProviderProfileException.Reason.PRIVATE_RESOURCE_NOT_FOUND));
    }

    private ProviderProfileException failure(ProviderProfileException.Reason reason) {
        return new ProviderProfileException(reason);
    }

    private int limit(Integer requestedLimit) {
        if (requestedLimit == null) {
            return DEFAULT_LIMIT;
        }
        if (requestedLimit < 1 || requestedLimit > MAX_LIMIT) {
            throw new ProviderIndexException();
        }
        return requestedLimit;
    }

    private String encode(UUID actorId, OffsetDateTime createdAt, UUID providerId) {
        try {
            return Base64.getUrlEncoder().withoutPadding().encodeToString(objectMapper.writeValueAsBytes(
                    new CursorPayload(1, actorId, createdAt.withOffsetSameInstant(ZoneOffset.UTC).toString(), providerId)));
        } catch (JacksonException exception) {
            throw new IllegalStateException("provider cursor cannot be encoded", exception);
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
                    || payload.createdAt() == null
                    || payload.providerId() == null) {
                throw new IllegalArgumentException();
            }
            var createdAt = OffsetDateTime.parse(payload.createdAt());
            var utcCreatedAt = createdAt.withOffsetSameInstant(ZoneOffset.UTC);
            if (createdAt.getNano() % 1_000 != 0
                    || utcCreatedAt.isBefore(POSTGRES_MINIMUM_TIMESTAMP)
                    || utcCreatedAt.isAfter(POSTGRES_MAXIMUM_TIMESTAMP)) {
                throw new IllegalArgumentException();
            }
            return new CursorPayload(payload.version(), payload.actorId(), utcCreatedAt.toString(), payload.providerId());
        } catch (IllegalArgumentException | DateTimeException | JacksonException exception) {
            throw new ProviderIndexException();
        }
    }

    private record CursorPayload(int version, UUID actorId, String createdAt, UUID providerId) {
        OffsetDateTime toCreatedAt() {
            return OffsetDateTime.parse(createdAt).withOffsetSameInstant(ZoneOffset.UTC);
        }
    }
}

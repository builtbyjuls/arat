package com.builtbyjuls.arat.providers.application;

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
import com.builtbyjuls.arat.providers.api.CreateProviderCommand;
import com.builtbyjuls.arat.providers.api.ProviderRepresentation;
import com.builtbyjuls.arat.providers.domain.ProviderOrganization;
import com.builtbyjuls.arat.providers.domain.ProviderOrganizationStatus;
import com.builtbyjuls.arat.providers.domain.ProviderStaffMembership;
import com.builtbyjuls.arat.providers.domain.ProviderStaffRole;
import com.builtbyjuls.arat.providers.domain.ProviderStaffStatus;
import com.builtbyjuls.arat.providers.domain.ProviderVerificationStatus;
import com.builtbyjuls.arat.providers.infrastructure.ProviderRepository;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Service
public class ProviderCreationService {

    public static final String CREATE_PROVIDER_OPERATION = "providers.create";

    private final ProviderRepository providerRepository;
    private final IdempotencyRepository idempotencyRepository;
    private final AuditEventWriter auditEventWriter;
    private final ObjectMapper objectMapper;

    public ProviderCreationService(
            ProviderRepository providerRepository,
            IdempotencyRepository idempotencyRepository,
            AuditEventWriter auditEventWriter,
            ObjectMapper objectMapper) {
        this.providerRepository = providerRepository;
        this.idempotencyRepository = idempotencyRepository;
        this.auditEventWriter = auditEventWriter;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public ProviderRepresentation create(CreateProviderCommand command) {
        var scope = new IdempotencyScope(command.actorId(), CREATE_PROVIDER_OPERATION, command.idempotencyKey());
        var fingerprint = RequestFingerprint.fromCanonicalFields(Map.of(
                "displayName", command.displayName(),
                "serviceAreaCodes", command.serviceAreaCodes(),
                "supportedCategories", command.supportedCategories()));
        var claim = idempotencyRepository.claim(scope, fingerprint);
        if (claim instanceof ClaimResult.Replay replay) {
            return responseFrom(replay.response());
        }
        rejectUnusableClaim(claim);

        var providerId = UUID.randomUUID();
        var createdAt = OffsetDateTime.now(ZoneOffset.UTC);
        var organization = new ProviderOrganization(
                providerId,
                command.displayName(),
                ProviderOrganizationStatus.ACTIVE,
                ProviderVerificationStatus.UNVERIFIED,
                1,
                1,
                createdAt,
                createdAt);
        var administrator = new ProviderStaffMembership(
                providerId,
                command.actorId(),
                ProviderStaffRole.ADMIN,
                ProviderStaffStatus.ACTIVE,
                createdAt,
                null);
        providerRepository.insert(
                organization,
                List.of(administrator),
                command.supportedCategories(),
                command.serviceAreaCodes());
        auditEventWriter.append(new AuditEvent(
                UUID.randomUUID(),
                command.actorId(),
                "provider.created",
                "provider",
                providerId,
                null,
                null,
                command.correlationId(),
                AuditMetadata.references(Map.of("providerId", providerId))));

        var representation = ProviderRepresentation.from(
                organization, command.supportedCategories(), command.serviceAreaCodes());
        idempotencyRepository.complete(scope, new CompletedIdempotencyResponse(
                201,
                ReplayState.from(objectMapper.valueToTree(representation), objectMapper),
                StoredReplayHeaders.from(Map.of(
                        "ETag", etag(organization.version()),
                        "Location", location(providerId))),
                providerId));
        return representation;
    }

    public static String etag(long version) {
        return "\"" + version + "\"";
    }

    public static String location(UUID providerId) {
        return "/api/v1/providers/" + providerId;
    }

    private ProviderRepresentation responseFrom(CompletedIdempotencyResponse response) {
        try {
            return objectMapper.treeToValue(response.replayState().value(), ProviderRepresentation.class);
        } catch (JacksonException exception) {
            throw new IllegalStateException("stored provider replay response is invalid", exception);
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

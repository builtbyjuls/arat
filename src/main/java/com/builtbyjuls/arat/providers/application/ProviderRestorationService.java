package com.builtbyjuls.arat.providers.application;

import com.builtbyjuls.arat.identity.api.PlatformRole;
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
import com.builtbyjuls.arat.providers.api.ProviderRestorationException;
import com.builtbyjuls.arat.providers.api.ProviderRestorationRepresentation;
import com.builtbyjuls.arat.providers.api.RestoreProviderCommand;
import com.builtbyjuls.arat.providers.domain.ProviderVerificationStatus;
import com.builtbyjuls.arat.providers.infrastructure.ProviderRepository;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Service
public class ProviderRestorationService {

    public static final String RESTORE_PROVIDER_OPERATION = "operations.providers.restoration.create";

    private final ProviderRepository providerRepository;
    private final IdempotencyRepository idempotencyRepository;
    private final AuditEventWriter auditEventWriter;
    private final ObjectMapper objectMapper;

    public ProviderRestorationService(
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
    public ProviderRestorationRepresentation restore(RestoreProviderCommand command) {
        if (!command.platformRoles().contains(PlatformRole.PLATFORM_OPERATOR)) {
            throw failure(ProviderRestorationException.Reason.FORBIDDEN_PLATFORM_ROLE);
        }

        var scope = new IdempotencyScope(command.actorId(), RESTORE_PROVIDER_OPERATION, command.idempotencyKey());
        var claim = idempotencyRepository.claim(scope, RequestFingerprint.fromCanonicalFields(Map.of(
                "providerId", command.providerId())));
        if (claim instanceof ClaimResult.Replay replay) {
            return responseFrom(replay.response());
        }
        rejectUnusableClaim(claim);

        var organization = providerRepository.findAndLockOrganization(command.providerId())
                .orElseThrow(() -> failure(ProviderRestorationException.Reason.PRIVATE_RESOURCE_NOT_FOUND));
        if (organization.verificationStatus() != ProviderVerificationStatus.SUSPENDED) {
            throw failure(ProviderRestorationException.Reason.INVALID_PROVIDER_STATE);
        }
        var updatedOrganization = providerRepository.restoreSuspendedProvider(command.providerId(), organization.version())
                .orElseThrow(() -> failure(ProviderRestorationException.Reason.INVALID_PROVIDER_STATE));

        auditEventWriter.append(new AuditEvent(
                UUID.randomUUID(),
                command.actorId(),
                "provider.restored",
                "provider",
                command.providerId(),
                null,
                null,
                command.correlationId(),
                AuditMetadata.references(Map.of("providerId", command.providerId()))));

        var representation = ProviderRestorationRepresentation.from(updatedOrganization);
        idempotencyRepository.complete(scope, new CompletedIdempotencyResponse(
                200,
                ReplayState.from(objectMapper.valueToTree(representation), objectMapper),
                StoredReplayHeaders.from(Map.of("ETag", ProviderCreationService.etag(updatedOrganization.version()))),
                command.providerId()));
        return representation;
    }

    private ProviderRestorationRepresentation responseFrom(CompletedIdempotencyResponse response) {
        try {
            return objectMapper.treeToValue(response.replayState().value(), ProviderRestorationRepresentation.class);
        } catch (JacksonException exception) {
            throw new IllegalStateException("stored provider restoration replay response is invalid", exception);
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

    private ProviderRestorationException failure(ProviderRestorationException.Reason reason) {
        return new ProviderRestorationException(reason);
    }
}

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
import com.builtbyjuls.arat.providers.api.ProviderSuspensionException;
import com.builtbyjuls.arat.providers.api.ProviderSuspensionRepresentation;
import com.builtbyjuls.arat.providers.api.SuspendProviderCommand;
import com.builtbyjuls.arat.providers.domain.ProviderSuspension;
import com.builtbyjuls.arat.providers.domain.ProviderVerificationStatus;
import com.builtbyjuls.arat.providers.infrastructure.ProviderRepository;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Service
public class ProviderSuspensionService {

    public static final String SUSPEND_PROVIDER_OPERATION = "operations.providers.suspension.create";

    private final ProviderRepository providerRepository;
    private final IdempotencyRepository idempotencyRepository;
    private final AuditEventWriter auditEventWriter;
    private final ObjectMapper objectMapper;

    public ProviderSuspensionService(
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
    public ProviderSuspensionRepresentation suspend(SuspendProviderCommand command) {
        if (!command.platformRoles().contains(PlatformRole.PLATFORM_OPERATOR)) {
            throw failure(ProviderSuspensionException.Reason.FORBIDDEN_PLATFORM_ROLE);
        }

        var scope = new IdempotencyScope(command.actorId(), SUSPEND_PROVIDER_OPERATION, command.idempotencyKey());
        var fingerprint = RequestFingerprint.fromCanonicalFields(Map.of(
                "providerId", command.providerId(),
                "reason", command.reason()));
        var claim = idempotencyRepository.claim(scope, fingerprint);
        if (claim instanceof ClaimResult.Replay replay) {
            return responseFrom(replay.response());
        }
        rejectUnusableClaim(claim);

        var organization = providerRepository.findAndLockOrganization(command.providerId())
                .orElseThrow(() -> failure(ProviderSuspensionException.Reason.PRIVATE_RESOURCE_NOT_FOUND));
        if (organization.verificationStatus() != ProviderVerificationStatus.VERIFIED) {
            throw failure(ProviderSuspensionException.Reason.INVALID_PROVIDER_STATE);
        }
        var updatedOrganization = providerRepository.suspendVerifiedProvider(
                        command.providerId(), organization.version())
                .orElseThrow(() -> failure(ProviderSuspensionException.Reason.INVALID_PROVIDER_STATE));

        var suspensionId = UUID.randomUUID();
        providerRepository.insertSuspension(new ProviderSuspension(
                suspensionId,
                command.providerId(),
                command.actorId(),
                command.reason()));
        auditEventWriter.append(new AuditEvent(
                UUID.randomUUID(),
                command.actorId(),
                "provider.suspended",
                "provider",
                command.providerId(),
                null,
                null,
                command.correlationId(),
                AuditMetadata.references(Map.of(
                        "providerId", command.providerId(),
                        "suspensionId", suspensionId))));

        var representation = ProviderSuspensionRepresentation.from(
                suspensionId, command.reason(), updatedOrganization);
        idempotencyRepository.complete(scope, new CompletedIdempotencyResponse(
                200,
                ReplayState.from(objectMapper.valueToTree(representation), objectMapper),
                StoredReplayHeaders.from(Map.of("ETag", ProviderCreationService.etag(updatedOrganization.version()))),
                suspensionId));
        return representation;
    }

    private ProviderSuspensionRepresentation responseFrom(CompletedIdempotencyResponse response) {
        try {
            return objectMapper.treeToValue(response.replayState().value(), ProviderSuspensionRepresentation.class);
        } catch (JacksonException exception) {
            throw new IllegalStateException("stored provider suspension replay response is invalid", exception);
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

    private ProviderSuspensionException failure(ProviderSuspensionException.Reason reason) {
        return new ProviderSuspensionException(reason);
    }
}

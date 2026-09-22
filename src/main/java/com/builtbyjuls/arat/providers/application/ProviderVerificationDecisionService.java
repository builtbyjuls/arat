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
import com.builtbyjuls.arat.providers.api.DecideProviderVerificationCommand;
import com.builtbyjuls.arat.providers.api.ProviderVerificationDecisionException;
import com.builtbyjuls.arat.providers.api.ProviderVerificationDecisionRepresentation;
import com.builtbyjuls.arat.providers.domain.ProviderVerificationDecision;
import com.builtbyjuls.arat.providers.domain.ProviderVerificationDecisionRecord;
import com.builtbyjuls.arat.providers.domain.ProviderVerificationStatus;
import com.builtbyjuls.arat.providers.infrastructure.ProviderRepository;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Service
public class ProviderVerificationDecisionService {

    public static final String DECIDE_VERIFICATION_OPERATION = "operations.providers.verification-decisions.decide";

    private final ProviderRepository providerRepository;
    private final IdempotencyRepository idempotencyRepository;
    private final AuditEventWriter auditEventWriter;
    private final ObjectMapper objectMapper;

    public ProviderVerificationDecisionService(
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
    public ProviderVerificationDecisionRepresentation decide(DecideProviderVerificationCommand command) {
        if (!command.platformRoles().contains(PlatformRole.PLATFORM_OPERATOR)) {
            throw failure(ProviderVerificationDecisionException.Reason.FORBIDDEN_PLATFORM_ROLE);
        }

        var scope = new IdempotencyScope(command.actorId(), DECIDE_VERIFICATION_OPERATION, command.idempotencyKey());
        var fingerprintFields = new LinkedHashMap<String, Object>();
        fingerprintFields.put("decision", command.decision());
        fingerprintFields.put("note", command.note());
        fingerprintFields.put("providerId", command.providerId());
        fingerprintFields.put("submissionId", command.submissionId());
        var claim = idempotencyRepository.claim(scope, RequestFingerprint.fromCanonicalFields(fingerprintFields));
        if (claim instanceof ClaimResult.Replay replay) {
            return responseFrom(replay.response());
        }
        rejectUnusableClaim(claim);

        var organization = providerRepository.findAndLockOrganization(command.providerId())
                .orElseThrow(() -> failure(ProviderVerificationDecisionException.Reason.PRIVATE_RESOURCE_NOT_FOUND));
        if (organization.verificationStatus() != ProviderVerificationStatus.PENDING) {
            throw failure(ProviderVerificationDecisionException.Reason.INVALID_PROVIDER_STATE);
        }
        var submissionId = providerRepository.findCurrentVerificationSubmissionId(command.providerId())
                .orElseThrow(() -> new IllegalStateException("pending provider has no verification submission"));
        if (!submissionId.equals(command.submissionId())) {
            throw failure(ProviderVerificationDecisionException.Reason.INVALID_PROVIDER_STATE);
        }
        var updatedOrganization = providerRepository.transitionPendingVerification(
                        command.providerId(), organization.version(), command.decision())
                .orElseThrow(() -> failure(ProviderVerificationDecisionException.Reason.INVALID_PROVIDER_STATE));

        var decisionId = UUID.randomUUID();
        providerRepository.insertVerificationDecision(new ProviderVerificationDecisionRecord(
                decisionId,
                command.providerId(),
                submissionId,
                command.actorId(),
                command.decision(),
                command.note()));
        auditEventWriter.append(new AuditEvent(
                UUID.randomUUID(),
                command.actorId(),
                command.decision() == ProviderVerificationDecision.ACCEPT
                        ? "provider.verification.accepted"
                        : "provider.verification.rejected",
                "provider",
                command.providerId(),
                null,
                null,
                command.correlationId(),
                AuditMetadata.references(Map.of(
                        "decisionId", decisionId,
                        "providerId", command.providerId(),
                        "submissionId", submissionId))));

        var representation = ProviderVerificationDecisionRepresentation.from(
                decisionId,
                submissionId,
                command.decision(),
                command.note(),
                updatedOrganization);
        idempotencyRepository.complete(scope, new CompletedIdempotencyResponse(
                200,
                ReplayState.from(objectMapper.valueToTree(representation), objectMapper),
                StoredReplayHeaders.from(Map.of("ETag", ProviderCreationService.etag(updatedOrganization.version()))),
                decisionId));
        return representation;
    }

    private ProviderVerificationDecisionRepresentation responseFrom(CompletedIdempotencyResponse response) {
        try {
            return objectMapper.treeToValue(response.replayState().value(), ProviderVerificationDecisionRepresentation.class);
        } catch (JacksonException exception) {
            throw new IllegalStateException("stored provider verification decision replay response is invalid", exception);
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

    private ProviderVerificationDecisionException failure(ProviderVerificationDecisionException.Reason reason) {
        return new ProviderVerificationDecisionException(reason);
    }
}

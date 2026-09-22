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
import com.builtbyjuls.arat.providers.api.ProviderVerificationException;
import com.builtbyjuls.arat.providers.api.ProviderVerificationSubmissionRepresentation;
import com.builtbyjuls.arat.providers.api.SubmitProviderVerificationCommand;
import com.builtbyjuls.arat.providers.domain.ProviderStaffRole;
import com.builtbyjuls.arat.providers.domain.ProviderStaffStatus;
import com.builtbyjuls.arat.providers.domain.ProviderVerificationStatus;
import com.builtbyjuls.arat.providers.domain.ProviderVerificationSubmission;
import com.builtbyjuls.arat.providers.infrastructure.ProviderRepository;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Service
public class ProviderVerificationSubmissionService {

    public static final String SUBMIT_VERIFICATION_OPERATION = "providers.verification-submissions.submit";

    private final ProviderRepository providerRepository;
    private final IdempotencyRepository idempotencyRepository;
    private final AuditEventWriter auditEventWriter;
    private final ObjectMapper objectMapper;

    public ProviderVerificationSubmissionService(
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
    public ProviderVerificationSubmissionRepresentation submit(SubmitProviderVerificationCommand command) {
        var scope = new IdempotencyScope(command.actorId(), SUBMIT_VERIFICATION_OPERATION, command.idempotencyKey());
        var fingerprint = RequestFingerprint.fromCanonicalFields(Map.of(
                "evidenceReferences", command.evidenceReferences(),
                "providerId", command.providerId()));
        var claim = idempotencyRepository.claim(scope, fingerprint);
        if (claim instanceof ClaimResult.Replay replay) {
            return responseFrom(replay.response());
        }
        rejectUnusableClaim(claim);

        var organization = providerRepository.findAndLockOrganization(command.providerId())
                .orElseThrow(() -> failure(ProviderVerificationException.Reason.PRIVATE_RESOURCE_NOT_FOUND));
        var membership = providerRepository.findStaffMembership(command.providerId(), command.actorId())
                .filter(candidate -> candidate.status() == ProviderStaffStatus.ACTIVE)
                .orElseThrow(() -> failure(ProviderVerificationException.Reason.PRIVATE_RESOURCE_NOT_FOUND));
        if (membership.role() != ProviderStaffRole.ADMIN) {
            throw failure(ProviderVerificationException.Reason.FORBIDDEN_ROLE);
        }
        if (organization.verificationStatus() != ProviderVerificationStatus.UNVERIFIED
                && organization.verificationStatus() != ProviderVerificationStatus.REJECTED) {
            throw failure(ProviderVerificationException.Reason.INVALID_PROVIDER_STATE);
        }

        var submissionId = UUID.randomUUID();
        var updatedOrganization = providerRepository.transitionVerificationToPending(
                        command.providerId(), organization.version(), submissionId)
                .orElseThrow(() -> failure(ProviderVerificationException.Reason.INVALID_PROVIDER_STATE));
        providerRepository.insertVerificationSubmission(new ProviderVerificationSubmission(
                submissionId,
                command.providerId(),
                command.actorId(),
                command.evidenceReferences()));
        auditEventWriter.append(new AuditEvent(
                UUID.randomUUID(),
                command.actorId(),
                "provider.verification.submitted",
                "provider",
                command.providerId(),
                null,
                null,
                command.correlationId(),
                AuditMetadata.references(Map.of("providerId", command.providerId(), "submissionId", submissionId))));

        var representation = ProviderVerificationSubmissionRepresentation.from(
                submissionId, updatedOrganization, command.evidenceReferences().size());
        idempotencyRepository.complete(scope, new CompletedIdempotencyResponse(
                200,
                ReplayState.from(objectMapper.valueToTree(representation), objectMapper),
                StoredReplayHeaders.from(Map.of("ETag", ProviderCreationService.etag(updatedOrganization.version()))),
                submissionId));
        return representation;
    }

    private ProviderVerificationSubmissionRepresentation responseFrom(CompletedIdempotencyResponse response) {
        try {
            return objectMapper.treeToValue(response.replayState().value(), ProviderVerificationSubmissionRepresentation.class);
        } catch (JacksonException exception) {
            throw new IllegalStateException("stored provider verification replay response is invalid", exception);
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

    private ProviderVerificationException failure(ProviderVerificationException.Reason reason) {
        return new ProviderVerificationException(reason);
    }
}

package com.builtbyjuls.arat.providers.application;

import com.builtbyjuls.arat.platform.audit.AuditEvent;
import com.builtbyjuls.arat.platform.audit.AuditEventWriter;
import com.builtbyjuls.arat.platform.audit.AuditMetadata;
import com.builtbyjuls.arat.providers.api.ProviderProfileException;
import com.builtbyjuls.arat.providers.api.ProviderRepresentation;
import com.builtbyjuls.arat.providers.api.ReplaceProviderProfileCommand;
import com.builtbyjuls.arat.providers.domain.ProviderStaffRole;
import com.builtbyjuls.arat.providers.domain.ProviderStaffStatus;
import com.builtbyjuls.arat.providers.infrastructure.ProviderRepository;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProviderProfileService {

    private final ProviderRepository providerRepository;
    private final AuditEventWriter auditEventWriter;

    public ProviderProfileService(ProviderRepository providerRepository, AuditEventWriter auditEventWriter) {
        this.providerRepository = providerRepository;
        this.auditEventWriter = auditEventWriter;
    }

    @Transactional
    public ProviderRepresentation read(UUID providerId, UUID actorId) {
        var organization = providerRepository.findAndLockOrganization(providerId)
                .orElseThrow(() -> failure(ProviderProfileException.Reason.PRIVATE_RESOURCE_NOT_FOUND));
        requireActiveStaff(providerId, actorId);
        return ProviderRepresentation.from(
                organization,
                providerRepository.findSupportedCategories(providerId),
                providerRepository.findServiceAreaCodes(providerId));
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
}

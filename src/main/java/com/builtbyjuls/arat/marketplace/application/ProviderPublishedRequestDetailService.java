package com.builtbyjuls.arat.marketplace.application;

import com.builtbyjuls.arat.marketplace.api.ProviderPublishedRequestDetailException;
import com.builtbyjuls.arat.marketplace.api.PublishedRequestRepresentation;
import com.builtbyjuls.arat.marketplace.infrastructure.RequestRecipientRepository;
import com.builtbyjuls.arat.planning.api.PlanningRequestAccess;
import com.builtbyjuls.arat.providers.api.ProviderEligibilityAccess;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProviderPublishedRequestDetailService {

    private final ProviderEligibilityAccess providerEligibilityAccess;
    private final RequestRecipientRepository recipientRepository;
    private final PlanningRequestAccess planningRequestAccess;

    public ProviderPublishedRequestDetailService(
            ProviderEligibilityAccess providerEligibilityAccess,
            RequestRecipientRepository recipientRepository,
            PlanningRequestAccess planningRequestAccess) {
        this.providerEligibilityAccess = providerEligibilityAccess;
        this.recipientRepository = recipientRepository;
        this.planningRequestAccess = planningRequestAccess;
    }

    @Transactional
    public PublishedRequestRepresentation find(UUID providerId, UUID requestId, UUID actorId) {
        if (!providerEligibilityAccess.lockAndHasActiveStaffAccess(providerId, actorId)) {
            throw notFound();
        }
        var recipient = recipientRepository.findActiveByRequestIdAndProviderId(requestId, providerId)
                .orElseThrow(this::notFound);
        if (!providerEligibilityAccess.lockAndHasCurrentVerifiedEligibility(
                providerId, recipient.providerEligibilityVersion())) {
            throw notFound();
        }
        var snapshot = planningRequestAccess.findProviderSafeSnapshot(requestId)
                .orElseThrow(this::notFound);
        return PublishedRequestRepresentation.from(snapshot);
    }

    private ProviderPublishedRequestDetailException notFound() {
        return new ProviderPublishedRequestDetailException();
    }
}

package com.builtbyjuls.arat.matching.application;

import com.builtbyjuls.arat.matching.api.MatchRequestRecipientsCommand;
import com.builtbyjuls.arat.matching.api.RequestRecipientCandidate;
import com.builtbyjuls.arat.matching.api.RequestRecipientSelection;
import com.builtbyjuls.arat.providers.api.ProviderEligibilityAccess;
import com.builtbyjuls.arat.providers.api.ProviderEligibilityCriteria;
import java.util.Comparator;
import java.util.LinkedHashMap;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RecipientMatchingService {

    private final ProviderEligibilityAccess providerEligibilityAccess;
    private final MatchingProperties properties;

    public RecipientMatchingService(
            ProviderEligibilityAccess providerEligibilityAccess, MatchingProperties properties) {
        this.providerEligibilityAccess = providerEligibilityAccess;
        this.properties = properties;
    }

    @Transactional(readOnly = true)
    public RequestRecipientSelection selectRecipients(MatchRequestRecipientsCommand command) {
        var cap = properties.recipientCap();
        var candidatesByProviderId = new LinkedHashMap<java.util.UUID, RequestRecipientCandidate>();
        providerEligibilityAccess.findVerifiedCandidates(
                        new ProviderEligibilityCriteria(command.category(), command.serviceAreaCode()), cap + 1)
                .stream()
                .map(candidate -> new RequestRecipientCandidate(candidate.providerId(), candidate.eligibilityVersion()))
                .sorted(Comparator.comparing((RequestRecipientCandidate candidate) -> candidate.providerId().toString())
                        .thenComparingLong(RequestRecipientCandidate::eligibilityVersion))
                .forEach(candidate -> candidatesByProviderId.putIfAbsent(candidate.providerId(), candidate));

        var candidates = candidatesByProviderId.values().stream().toList();
        if (candidates.isEmpty()) {
            return new RequestRecipientSelection.NoCandidates();
        }
        if (candidates.size() > cap) {
            return new RequestRecipientSelection.CapExceeded(cap);
        }
        return new RequestRecipientSelection.Candidates(candidates);
    }
}

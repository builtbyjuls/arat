package com.builtbyjuls.arat.providers.api;

import com.builtbyjuls.arat.providers.domain.ProviderOrganizationStatus;
import com.builtbyjuls.arat.providers.domain.ProviderStaffStatus;
import com.builtbyjuls.arat.providers.domain.ProviderVerificationStatus;
import com.builtbyjuls.arat.providers.infrastructure.ProviderRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Public Providers boundary for eligibility observations and provider-context guards.
 */
@Component
public class ProviderEligibilityAccess {

    private final ProviderRepository providerRepository;

    public ProviderEligibilityAccess(ProviderRepository providerRepository) {
        this.providerRepository = providerRepository;
    }

    @Transactional(readOnly = true)
    public List<ProviderEligibilityCandidate> findVerifiedCandidates(
            ProviderEligibilityCriteria criteria, int maximumCandidateCount) {
        return providerRepository.findVerifiedEligibilityCandidates(criteria, requireMaximumCandidateCount(maximumCandidateCount));
    }

    @Transactional(readOnly = true)
    public boolean hasActiveStaffAccess(UUID providerId, UUID accountId) {
        return providerRepository.findStaffMembership(providerId, accountId)
                .map(membership -> membership.status() == ProviderStaffStatus.ACTIVE)
                .orElse(false);
    }

    @Transactional(readOnly = true)
    public boolean hasCurrentVerifiedEligibility(UUID providerId, long capturedEligibilityVersion) {
        var requiredEligibilityVersion = requireEligibilityVersion(capturedEligibilityVersion);
        return providerRepository.findById(providerId)
                .map(organization -> organization.status() == ProviderOrganizationStatus.ACTIVE
                        && organization.verificationStatus() == ProviderVerificationStatus.VERIFIED
                        && organization.eligibilityVersion() == requiredEligibilityVersion)
                .orElse(false);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public boolean lockAndHasActiveStaffAccess(UUID providerId, UUID accountId) {
        if (providerRepository.findAndLockOrganization(providerId).isEmpty()) {
            return false;
        }
        return providerRepository.findStaffMembership(providerId, accountId)
                .map(membership -> membership.status() == ProviderStaffStatus.ACTIVE)
                .orElse(false);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public boolean lockAndHasCurrentVerifiedEligibility(UUID providerId, long capturedEligibilityVersion) {
        var requiredEligibilityVersion = requireEligibilityVersion(capturedEligibilityVersion);
        return providerRepository.findAndLockOrganization(providerId)
                .map(organization -> organization.status() == ProviderOrganizationStatus.ACTIVE
                        && organization.verificationStatus() == ProviderVerificationStatus.VERIFIED
                        && organization.eligibilityVersion() == requiredEligibilityVersion)
                .orElse(false);
    }

    private int requireMaximumCandidateCount(int maximumCandidateCount) {
        if (maximumCandidateCount < 1 || maximumCandidateCount > 501) {
            throw new IllegalArgumentException("maximumCandidateCount must be from 1 to 501");
        }
        return maximumCandidateCount;
    }

    private long requireEligibilityVersion(long capturedEligibilityVersion) {
        if (capturedEligibilityVersion < 1) {
            throw new IllegalArgumentException("capturedEligibilityVersion must be positive");
        }
        return capturedEligibilityVersion;
    }
}

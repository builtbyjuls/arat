package com.builtbyjuls.arat.matching.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.builtbyjuls.arat.matching.api.MatchRequestRecipientsCommand;
import com.builtbyjuls.arat.matching.api.RequestRecipientCandidate;
import com.builtbyjuls.arat.matching.api.RequestRecipientSelection;
import com.builtbyjuls.arat.providers.api.ProviderEligibilityAccess;
import com.builtbyjuls.arat.providers.api.ProviderEligibilityCandidate;
import com.builtbyjuls.arat.providers.api.ProviderEligibilityCriteria;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class RecipientMatchingServiceTest {

    private static final UUID FIRST_PROVIDER_ID = UUID.fromString("10000000-0000-4000-8000-000000000001");
    private static final UUID SECOND_PROVIDER_ID = UUID.fromString("20000000-0000-4000-8000-000000000002");
    private static final UUID THIRD_PROVIDER_ID = UUID.fromString("30000000-0000-4000-8000-000000000003");
    private static final UUID LAST_LOWER_HALF_PROVIDER_ID = UUID.fromString("7fffffff-ffff-4fff-8fff-ffffffffffff");
    private static final UUID FIRST_UPPER_HALF_PROVIDER_ID = UUID.fromString("80000000-0000-4000-8000-000000000000");

    @Mock
    private ProviderEligibilityAccess providerEligibilityAccess;

    @Test
    void returnsNoCandidatesWhenTheProviderBoundaryFindsNone() {
        var service = new RecipientMatchingService(providerEligibilityAccess, properties(2));
        var command = new MatchRequestRecipientsCommand("COURT", "BGC");
        when(providerEligibilityAccess.findVerifiedCandidates(new ProviderEligibilityCriteria("COURT", "BGC"), 3))
                .thenReturn(List.of());

        assertThat(service.selectRecipients(command)).isInstanceOf(RequestRecipientSelection.NoCandidates.class);
    }

    @Test
    void deduplicatesAndSortsCandidateObservationsIndependentOfProviderReturnOrder() {
        var service = new RecipientMatchingService(providerEligibilityAccess, properties(3));
        when(providerEligibilityAccess.findVerifiedCandidates(new ProviderEligibilityCriteria("COURT", "BGC"), 4))
                .thenReturn(List.of(
                        new ProviderEligibilityCandidate(THIRD_PROVIDER_ID, 9),
                        new ProviderEligibilityCandidate(FIRST_PROVIDER_ID, 3),
                        new ProviderEligibilityCandidate(SECOND_PROVIDER_ID, 7),
                        new ProviderEligibilityCandidate(FIRST_PROVIDER_ID, 3)));

        assertThat(service.selectRecipients(new MatchRequestRecipientsCommand("COURT", "BGC")))
                .isEqualTo(new RequestRecipientSelection.Candidates(List.of(
                        new RequestRecipientCandidate(FIRST_PROVIDER_ID, 3),
                        new RequestRecipientCandidate(SECOND_PROVIDER_ID, 7),
                        new RequestRecipientCandidate(THIRD_PROVIDER_ID, 9))));
    }

    @Test
    void reportsCapOverflowWithoutTruncatingTheCandidateOutcome() {
        var service = new RecipientMatchingService(providerEligibilityAccess, properties(2));
        var command = new MatchRequestRecipientsCommand("COURT", "BGC");
        when(providerEligibilityAccess.findVerifiedCandidates(new ProviderEligibilityCriteria("COURT", "BGC"), 3))
                .thenReturn(List.of(
                        new ProviderEligibilityCandidate(FIRST_PROVIDER_ID, 3),
                        new ProviderEligibilityCandidate(SECOND_PROVIDER_ID, 7),
                        new ProviderEligibilityCandidate(THIRD_PROVIDER_ID, 9)));

        assertThat(service.selectRecipients(command)).isEqualTo(new RequestRecipientSelection.CapExceeded(2));
    }

    @Test
    void preservesPostgreSqlUuidOrderAcrossTheSignedComparisonBoundary() {
        var service = new RecipientMatchingService(providerEligibilityAccess, properties(2));
        when(providerEligibilityAccess.findVerifiedCandidates(new ProviderEligibilityCriteria("COURT", "BGC"), 3))
                .thenReturn(List.of(
                        new ProviderEligibilityCandidate(FIRST_UPPER_HALF_PROVIDER_ID, 7),
                        new ProviderEligibilityCandidate(LAST_LOWER_HALF_PROVIDER_ID, 3)));

        assertThat(service.selectRecipients(new MatchRequestRecipientsCommand("COURT", "BGC")))
                .isEqualTo(new RequestRecipientSelection.Candidates(List.of(
                        new RequestRecipientCandidate(LAST_LOWER_HALF_PROVIDER_ID, 3),
                        new RequestRecipientCandidate(FIRST_UPPER_HALF_PROVIDER_ID, 7))));
    }

    @Test
    void usesTheDefaultCapOfOneHundredPlusOneToDetectOverflow() {
        var properties = new MatchingProperties();
        var service = new RecipientMatchingService(providerEligibilityAccess, properties);
        var criteria = new ProviderEligibilityCriteria("COURT", "BGC");
        when(providerEligibilityAccess.findVerifiedCandidates(criteria, 101)).thenReturn(List.of(
                new ProviderEligibilityCandidate(FIRST_PROVIDER_ID, 3)));

        service.selectRecipients(new MatchRequestRecipientsCommand("COURT", "BGC"));

        var count = ArgumentCaptor.forClass(Integer.class);
        verify(providerEligibilityAccess).findVerifiedCandidates(org.mockito.ArgumentMatchers.eq(criteria), count.capture());
        assertThat(properties.recipientCap()).isEqualTo(100);
        assertThat(count.getValue()).isEqualTo(101);
    }

    private MatchingProperties properties(int recipientCap) {
        var properties = new MatchingProperties();
        properties.setRecipientCap(recipientCap);
        return properties;
    }
}

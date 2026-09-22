package com.builtbyjuls.arat.matching.api;

import com.builtbyjuls.arat.matching.application.RecipientMatchingService;
import org.springframework.stereotype.Component;

/**
 * Public Matching boundary for selecting a provider recipient audience.
 */
@Component
public class MatchingAccess {

    private final RecipientMatchingService recipientMatchingService;

    public MatchingAccess(RecipientMatchingService recipientMatchingService) {
        this.recipientMatchingService = recipientMatchingService;
    }

    public RequestRecipientSelection selectRecipients(MatchRequestRecipientsCommand command) {
        return recipientMatchingService.selectRecipients(command);
    }
}

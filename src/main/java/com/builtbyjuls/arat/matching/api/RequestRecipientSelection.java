package com.builtbyjuls.arat.matching.api;

import java.util.List;

/**
 * The stateless result of applying the recipient matching rule.
 */
public sealed interface RequestRecipientSelection permits RequestRecipientSelection.Candidates,
        RequestRecipientSelection.NoCandidates, RequestRecipientSelection.CapExceeded {

    record Candidates(List<RequestRecipientCandidate> candidates) implements RequestRecipientSelection {
        public Candidates {
            candidates = List.copyOf(candidates);
            if (candidates.isEmpty()) {
                throw new IllegalArgumentException("candidates must not be empty");
            }
        }
    }

    record NoCandidates() implements RequestRecipientSelection {
    }

    record CapExceeded(int recipientCap) implements RequestRecipientSelection {
        public CapExceeded {
            if (recipientCap < 1) {
                throw new IllegalArgumentException("recipientCap must be positive");
            }
        }
    }
}

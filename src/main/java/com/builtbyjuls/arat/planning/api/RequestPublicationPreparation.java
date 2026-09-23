package com.builtbyjuls.arat.planning.api;

import java.util.UUID;

public record RequestPublicationPreparation(
        long planVersion,
        UUID currentRequestId,
        ProviderSafeRequestTerms terms) {
}

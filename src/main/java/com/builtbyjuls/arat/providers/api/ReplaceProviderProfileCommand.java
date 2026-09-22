package com.builtbyjuls.arat.providers.api;

import com.builtbyjuls.arat.providers.domain.ProviderCategory;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record ReplaceProviderProfileCommand(
        UUID actorId,
        UUID providerId,
        long expectedVersion,
        String displayName,
        List<ProviderCategory> supportedCategories,
        List<String> serviceAreaCodes,
        String correlationId) {

    public ReplaceProviderProfileCommand {
        Objects.requireNonNull(actorId, "actorId must not be null");
        Objects.requireNonNull(providerId, "providerId must not be null");
        if (expectedVersion < 1) {
            throw new IllegalArgumentException("expectedVersion must be positive");
        }
        var profile = new CreateProviderCommand(
                actorId, "profile-replacement", displayName, supportedCategories, serviceAreaCodes, correlationId);
        displayName = profile.displayName();
        supportedCategories = profile.supportedCategories();
        serviceAreaCodes = profile.serviceAreaCodes();
        correlationId = profile.correlationId();
    }
}

package com.builtbyjuls.arat.providers.api;

import com.builtbyjuls.arat.providers.domain.ProviderCategory;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record CreateProviderCommand(
        UUID actorId,
        String idempotencyKey,
        String displayName,
        List<ProviderCategory> supportedCategories,
        List<String> serviceAreaCodes,
        String correlationId) {

    public CreateProviderCommand {
        Objects.requireNonNull(actorId, "actorId must not be null");
        idempotencyKey = requiredText(idempotencyKey, "idempotencyKey", 255);
        displayName = requiredText(displayName, "displayName", 120).trim();
        supportedCategories = canonicalCategories(supportedCategories);
        serviceAreaCodes = canonicalAreaCodes(serviceAreaCodes);
        correlationId = requiredText(correlationId, "correlationId", 120);
    }

    private static List<ProviderCategory> canonicalCategories(List<ProviderCategory> categories) {
        if (categories == null || categories.isEmpty() || categories.size() > 10 || categories.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("supportedCategories must contain from 1 to 10 values");
        }
        var canonical = categories.stream().sorted(Comparator.comparing(Enum::name)).toList();
        if (canonical.stream().distinct().count() != canonical.size()) {
            throw new IllegalArgumentException("supportedCategories must be unique");
        }
        return canonical;
    }

    private static List<String> canonicalAreaCodes(List<String> areaCodes) {
        if (areaCodes == null || areaCodes.isEmpty() || areaCodes.size() > 20) {
            throw new IllegalArgumentException("serviceAreaCodes must contain from 1 to 20 values");
        }
        var canonical = areaCodes.stream()
                .map(areaCode -> requiredText(areaCode, "serviceAreaCode", 64).trim())
                .sorted()
                .toList();
        if (canonical.stream().distinct().count() != canonical.size()) {
            throw new IllegalArgumentException("serviceAreaCodes must be unique");
        }
        return canonical;
    }

    private static String requiredText(String value, String name, int maximumLength) {
        if (value == null || value.isBlank() || value.trim().length() > maximumLength) {
            throw new IllegalArgumentException(name + " must be non-blank and at most " + maximumLength + " characters");
        }
        return value;
    }
}

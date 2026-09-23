package com.builtbyjuls.arat.marketplace.api;

import com.builtbyjuls.arat.planning.api.ProviderSafeRequestSnapshot;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record PublishedRequestRepresentation(
        UUID requestId,
        long requestVersion,
        OffsetDateTime publishedAt,
        String state,
        boolean actionable,
        String category,
        String timeZone,
        AreaRepresentation area,
        WindowRepresentation requestedWindow,
        HeadcountRepresentation headcount,
        BudgetRepresentation budget,
        List<String> mustHaves,
        Map<String, Object> categoryAttributes,
        String providerSafeNotes,
        OffsetDateTime offerDeadline) {

    public PublishedRequestRepresentation {
        mustHaves = List.copyOf(mustHaves);
        categoryAttributes = Map.copyOf(categoryAttributes);
    }

    public static PublishedRequestRepresentation from(ProviderSafeRequestSnapshot snapshot) {
        var budget = snapshot.budgetMinimumMinorUnits() == null
                ? null
                : new BudgetRepresentation(
                        "PHP",
                        amount(snapshot.budgetMinimumMinorUnits()),
                        amount(snapshot.budgetMaximumMinorUnits()));
        return new PublishedRequestRepresentation(
                snapshot.requestId(),
                snapshot.requestVersion(),
                snapshot.publishedAt(),
                snapshot.state(),
                snapshot.actionable(),
                snapshot.category(),
                snapshot.timeZone(),
                new AreaRepresentation(snapshot.areaCode(), snapshot.radiusKm()),
                new WindowRepresentation(snapshot.requestedStartsAt(), snapshot.requestedEndsAt()),
                new HeadcountRepresentation(snapshot.minimumHeadcount(), snapshot.maximumHeadcount()),
                budget,
                snapshot.mustHaves(),
                snapshot.categoryAttributes(),
                snapshot.providerSafeNotes(),
                snapshot.offerDeadline());
    }

    private static String amount(long minorUnits) {
        return BigDecimal.valueOf(minorUnits, 2).toPlainString();
    }

    public record AreaRepresentation(String code, int radiusKm) {
    }

    public record WindowRepresentation(OffsetDateTime startAt, OffsetDateTime endAt) {
    }

    public record HeadcountRepresentation(int minimum, int maximum) {
    }

    public record BudgetRepresentation(String currency, String minimumAmount, String maximumAmount) {
    }
}

package com.builtbyjuls.arat.planning.api;

import jakarta.validation.constraints.NotNull;
import java.time.OffsetDateTime;
import java.util.UUID;

public record FinalizeRequirementsRequest(
        @NotNull UUID candidateWindowId,
        @NotNull OffsetDateTime offerDeadline) {
}

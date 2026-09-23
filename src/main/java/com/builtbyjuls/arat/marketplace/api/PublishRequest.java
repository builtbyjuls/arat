package com.builtbyjuls.arat.marketplace.api;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record PublishRequest(@NotNull UUID finalizationId) {
}

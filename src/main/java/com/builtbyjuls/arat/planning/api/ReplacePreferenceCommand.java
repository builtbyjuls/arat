package com.builtbyjuls.arat.planning.api;

import java.util.UUID;

public record ReplacePreferenceCommand(
        UUID actorId, UUID planId, long expectedPreferenceVersion, CreatePreferenceRequest request) {
}

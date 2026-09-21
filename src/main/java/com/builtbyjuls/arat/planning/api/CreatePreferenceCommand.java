package com.builtbyjuls.arat.planning.api;

import java.util.UUID;

public record CreatePreferenceCommand(UUID actorId, UUID planId, CreatePreferenceRequest request) {
}

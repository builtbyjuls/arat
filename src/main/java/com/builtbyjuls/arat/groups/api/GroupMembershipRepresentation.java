package com.builtbyjuls.arat.groups.api;

import java.util.UUID;

public record GroupMembershipRepresentation(
        UUID groupId,
        UUID accountId,
        String role,
        long groupVersion) {
}

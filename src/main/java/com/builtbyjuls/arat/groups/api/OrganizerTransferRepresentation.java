package com.builtbyjuls.arat.groups.api;

import java.util.UUID;

public record OrganizerTransferRepresentation(
        UUID groupId,
        UUID previousOrganizerAccountId,
        UUID organizerAccountId,
        long groupVersion) {
}

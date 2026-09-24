package com.builtbyjuls.arat.groups.domain;

import java.util.Objects;

public record GroupListing(Group group, MembershipRole role) {

    public GroupListing {
        Objects.requireNonNull(group, "group must not be null");
        Objects.requireNonNull(role, "role must not be null");
    }
}

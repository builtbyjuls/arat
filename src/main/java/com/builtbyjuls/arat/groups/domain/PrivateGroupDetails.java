package com.builtbyjuls.arat.groups.domain;

import java.util.List;
import java.util.Objects;

public record PrivateGroupDetails(Group group, List<Membership> activeMembers) {

    public PrivateGroupDetails {
        Objects.requireNonNull(group, "group must not be null");
        activeMembers = List.copyOf(Objects.requireNonNull(activeMembers, "activeMembers must not be null"));
    }
}

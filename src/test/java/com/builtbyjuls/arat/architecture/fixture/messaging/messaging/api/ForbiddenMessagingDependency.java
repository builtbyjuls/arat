package com.builtbyjuls.arat.architecture.fixture.messaging.messaging.api;

import com.builtbyjuls.arat.architecture.fixture.messaging.groups.api.GroupsApi;

public class ForbiddenMessagingDependency {

    private final GroupsApi groupsApi;

    public ForbiddenMessagingDependency(GroupsApi groupsApi) {
        this.groupsApi = groupsApi;
    }

    public GroupsApi groupsApi() {
        return groupsApi;
    }
}

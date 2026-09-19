package com.builtbyjuls.arat.groups.api;

import com.builtbyjuls.arat.groups.infrastructure.GroupRepository;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Public Groups boundary for commands that must serialize with membership changes.
 */
@Component
public class GroupMembershipAccess {

    private final GroupRepository groupRepository;

    public GroupMembershipAccess(GroupRepository groupRepository) {
        this.groupRepository = groupRepository;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public boolean lockAndHasActiveMembership(UUID groupId, UUID accountId) {
        if (groupRepository.findAndLockGroup(groupId).isEmpty()) {
            return false;
        }
        return groupRepository.findActiveMembership(groupId, accountId).isPresent();
    }
}

package com.builtbyjuls.arat.groups.api;

import com.builtbyjuls.arat.groups.infrastructure.GroupRepository;
import com.builtbyjuls.arat.groups.domain.MembershipRole;
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

    @Transactional(propagation = Propagation.MANDATORY)
    public boolean lockGroup(UUID groupId) {
        return groupRepository.findAndLockGroup(groupId).isPresent();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public boolean hasActiveOrganizer(UUID groupId, UUID accountId) {
        return groupRepository.findActiveMembership(groupId, accountId)
                .map(membership -> membership.role() == MembershipRole.ORGANIZER)
                .orElse(false);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public boolean hasActiveMembership(UUID groupId, UUID accountId) {
        return groupRepository.findActiveMembership(groupId, accountId).isPresent();
    }
}

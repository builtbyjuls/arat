package com.builtbyjuls.arat.groups.application;

import com.builtbyjuls.arat.groups.api.GroupDetailRepresentation;
import com.builtbyjuls.arat.groups.infrastructure.GroupRepository;
import com.builtbyjuls.arat.identity.api.AccountDirectory;
import java.util.UUID;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class GroupQueryService {

    private final GroupRepository groupRepository;
    private final AccountDirectory accountDirectory;

    public GroupQueryService(GroupRepository groupRepository, AccountDirectory accountDirectory) {
        this.groupRepository = groupRepository;
        this.accountDirectory = accountDirectory;
    }

    @Transactional(readOnly = true)
    public Optional<GroupDetailRepresentation> findPrivate(UUID groupId, UUID accountId) {
        return groupRepository.findPrivateDetails(groupId, accountId)
                .map(details -> GroupDetailRepresentation.from(
                        details,
                        accountDirectory.findByIds(details.activeMembers().stream()
                                .map(member -> member.accountId())
                                .toList())));
    }
}

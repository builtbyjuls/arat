package com.builtbyjuls.arat.groups.api;

import com.builtbyjuls.arat.groups.domain.PrivateGroupDetails;
import com.builtbyjuls.arat.identity.api.Account;
import com.builtbyjuls.arat.groups.domain.Membership;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record GroupDetailRepresentation(
        UUID groupId,
        String name,
        String description,
        long version,
        List<MemberRepresentation> members) {

    public static GroupDetailRepresentation from(PrivateGroupDetails details, Map<UUID, Account> accounts) {
        return new GroupDetailRepresentation(
                details.group().groupId(),
                details.group().name(),
                details.group().description(),
                details.group().version(),
                details.activeMembers().stream()
                        .map(member -> member(member, accounts.get(member.accountId())))
                        .toList());
    }

    private static MemberRepresentation member(Membership membership, Account account) {
        if (account == null) {
            throw new IllegalStateException("active group member account is missing");
        }
        return new MemberRepresentation(account.accountId(), account.displayName(), membership.role().name());
    }

    public record MemberRepresentation(UUID accountId, String displayName, String role) {
    }
}

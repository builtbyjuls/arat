package com.builtbyjuls.arat.identity.testing;

import com.builtbyjuls.arat.identity.api.Account;
import com.builtbyjuls.arat.identity.api.PlatformRole;
import com.builtbyjuls.arat.identity.security.LocalAccountFixtures;
import java.util.Set;

public enum AratAccountFixture {
    OWNER(LocalAccountFixtures.OWNER),
    MEMBER(LocalAccountFixtures.MEMBER),
    OUTSIDER(LocalAccountFixtures.OUTSIDER),
    PROVIDER(LocalAccountFixtures.PROVIDER),
    OPERATOR(LocalAccountFixtures.OPERATOR);

    private final Account account;
    private final Set<PlatformRole> platformRoles;

    AratAccountFixture(Account account) {
        this.account = account;
        this.platformRoles = LocalAccountFixtures.platformRoles(account);
    }

    public Account account() {
        return account;
    }

    public Set<PlatformRole> platformRoles() {
        return platformRoles;
    }
}

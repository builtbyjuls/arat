package com.builtbyjuls.arat.identity.testing;

import com.builtbyjuls.arat.identity.api.Account;
import com.builtbyjuls.arat.identity.security.LocalAccountFixtures;

public enum AratAccountFixture {
    OWNER(LocalAccountFixtures.OWNER),
    MEMBER(LocalAccountFixtures.MEMBER),
    OUTSIDER(LocalAccountFixtures.OUTSIDER);

    private final Account account;

    AratAccountFixture(Account account) {
        this.account = account;
    }

    public Account account() {
        return account;
    }
}

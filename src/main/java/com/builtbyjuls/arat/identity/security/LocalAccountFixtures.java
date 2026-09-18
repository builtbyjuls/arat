package com.builtbyjuls.arat.identity.security;

import com.builtbyjuls.arat.identity.api.Account;
import java.util.List;
import java.util.UUID;

public final class LocalAccountFixtures {

    public static final Account OWNER = account(
            "10000000-0000-4000-8000-000000000001", "Ari Organizer");
    public static final Account MEMBER = account(
            "10000000-0000-4000-8000-000000000002", "Bea Member");
    public static final Account OUTSIDER = account(
            "10000000-0000-4000-8000-000000000003", "Cruz Outsider");

    private LocalAccountFixtures() {
    }

    public static List<Account> all() {
        return List.of(OWNER, MEMBER, OUTSIDER);
    }

    private static Account account(String accountId, String displayName) {
        return new Account(UUID.fromString(accountId), displayName);
    }
}

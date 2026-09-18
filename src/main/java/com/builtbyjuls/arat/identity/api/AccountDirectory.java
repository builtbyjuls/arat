package com.builtbyjuls.arat.identity.api;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;

public interface AccountDirectory {

    Map<UUID, Account> findByIds(Collection<UUID> accountIds);
}

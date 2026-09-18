package com.builtbyjuls.arat.identity.infrastructure;

import com.builtbyjuls.arat.identity.api.Account;
import com.builtbyjuls.arat.identity.api.AccountDirectory;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

@Component
public class JdbcAccountDirectory implements AccountDirectory {

    private final JdbcClient jdbcClient;

    public JdbcAccountDirectory(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Override
    public Map<UUID, Account> findByIds(Collection<UUID> accountIds) {
        if (accountIds.isEmpty()) {
            return Map.of();
        }
        return jdbcClient.sql("""
                        SELECT account_id, display_name
                        FROM identity_account
                        WHERE account_id IN (:accountIds)
                        """)
                .param("accountIds", accountIds)
                .query(this::mapAccount)
                .list()
                .stream()
                .collect(java.util.stream.Collectors.toUnmodifiableMap(Account::accountId, account -> account));
    }

    private Account mapAccount(ResultSet resultSet, int rowNum) throws SQLException {
        return new Account(
                resultSet.getObject("account_id", UUID.class),
                resultSet.getString("display_name"));
    }
}

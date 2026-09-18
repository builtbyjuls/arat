package com.builtbyjuls.arat.identity.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.builtbyjuls.arat.PostgreSqlIntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(properties = "arat.test.account-migration-context=true")
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class AccountMigrationIT extends PostgreSqlIntegrationTest {

    @Autowired
    private JdbcClient jdbcClient;

    @Test
    void createsAnEmptyAccountTableUntilAProfileLoadsFixtures() {
        assertThat(jdbcClient.sql("SELECT count(*) FROM identity_account")
                .query(Long.class)
                .single()).isZero();

        jdbcClient.sql("""
                        INSERT INTO identity_account (account_id, display_name)
                        VALUES (:accountId, :displayName)
                        """)
                .param("accountId", LocalAccountFixtures.OWNER.accountId())
                .param("displayName", LocalAccountFixtures.OWNER.displayName())
                .update();

        var account = jdbcClient.sql("""
                        SELECT account_id, display_name
                        FROM identity_account
                        WHERE account_id = :accountId
                        """)
                .param("accountId", LocalAccountFixtures.OWNER.accountId())
                .query((rs, rowNum) -> new AccountRow(rs.getObject("account_id", UUID.class), rs.getString("display_name")))
                .single();
        assertThat(account.accountId()).isEqualTo(LocalAccountFixtures.OWNER.accountId());
        assertThat(account.displayName()).isEqualTo(LocalAccountFixtures.OWNER.displayName());
    }

    private record AccountRow(UUID accountId, String displayName) {
    }
}

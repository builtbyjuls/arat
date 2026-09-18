package com.builtbyjuls.arat.identity.security;

import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;

@Configuration(proxyBeanMethods = false)
@Profile("local & !prod")
class LocalAccountFixtureLoader {

    @Bean
    ApplicationRunner loadLocalAccountFixtures(JdbcClient jdbcClient) {
        return args -> LocalAccountFixtures.all().forEach(account -> jdbcClient.sql("""
                        INSERT INTO identity_account (account_id, display_name)
                        VALUES (:accountId, :displayName)
                        ON CONFLICT (account_id) DO UPDATE
                        SET display_name = EXCLUDED.display_name
                        """)
                .param("accountId", account.accountId())
                .param("displayName", account.displayName())
                .update());
    }
}

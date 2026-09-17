package com.builtbyjuls.arat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.time.Duration;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class AratApplicationIT extends PostgreSqlIntegrationTest {

    @Autowired
    private Flyway flyway;

    @Autowired
    private JdbcClient jdbcClient;

    @Test
    void contextLoads() {
    }

    @Test
    void appliesAndValidatesMigration001OnPostgreSql18() {
        assertThat(jdbcClient.sql("""
                SELECT success
                FROM flyway_schema_history
                WHERE version = '001'
                """).query(Boolean.class).single()).isTrue();
        assertThat(flyway.validateWithResult().validationSuccessful).isTrue();
        assertThat(flyway.info().pending()).isEmpty();
        assertThat(flyway.info().all()).noneMatch(migration -> migration.getState().isFailed());

        var serverVersion = jdbcClient.sql("SHOW server_version_num")
                .query(String.class)
                .single();
        assertThat(Integer.parseInt(serverVersion) / 10_000).isEqualTo(18);
        assertThat(jdbcClient.sql("SELECT 1").query(Integer.class).single()).isOne();
    }

    @Test
    void rejectsInvalidDatabaseConfigurationWithoutExposingCredentials() {
        var secret = "database-password-must-not-appear";

        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> assertThatThrownBy(() -> {
            try (var context = new SpringApplicationBuilder(AratApplication.class)
                    .web(WebApplicationType.NONE)
                    .run(
                            "--spring.datasource.url=not-a-jdbc-url",
                            "--spring.datasource.username=arat",
                            "--spring.datasource.password=" + secret)) {
            }
        })
                .hasMessageContaining("'url' must start with \"jdbc\"")
                .hasMessageNotContaining(secret));
    }
}

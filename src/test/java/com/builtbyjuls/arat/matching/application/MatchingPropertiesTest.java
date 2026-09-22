package com.builtbyjuls.arat.matching.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class MatchingPropertiesTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(MatchingConfiguration.class);

    @Test
    void acceptsTheDocumentedConfigurationBounds() {
        contextRunner.withPropertyValues("arat.matching.recipient-cap=1")
                .run(context -> assertThat(context.getBean(MatchingProperties.class).recipientCap()).isOne());
        contextRunner.withPropertyValues("arat.matching.recipient-cap=500")
                .run(context -> assertThat(context.getBean(MatchingProperties.class).recipientCap()).isEqualTo(500));
    }

    @Test
    void rejectsConfigurationOutsideTheRecipientCapBoundsAtStartup() {
        contextRunner.withPropertyValues("arat.matching.recipient-cap=0")
                .run(context -> assertThat(context.getStartupFailure()).isNotNull());
        contextRunner.withPropertyValues("arat.matching.recipient-cap=501")
                .run(context -> assertThat(context.getStartupFailure()).isNotNull());
    }
}

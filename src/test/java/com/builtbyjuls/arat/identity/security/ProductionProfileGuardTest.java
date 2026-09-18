package com.builtbyjuls.arat.identity.security;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.builtbyjuls.arat.AratApplication;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;

class ProductionProfileGuardTest {

    @ParameterizedTest
    @MethodSource("conflictingProfiles")
    void rejectsProductionProfileCollisions(String firstProfile, String secondProfile) {
        assertThatThrownBy(() -> new SpringApplicationBuilder(AratApplication.class)
                .web(WebApplicationType.NONE)
                .profiles(firstProfile, secondProfile)
                .run())
                .hasStackTraceContaining("The prod profile cannot be combined with local or compose.");
    }

    private static java.util.stream.Stream<org.junit.jupiter.params.provider.Arguments> conflictingProfiles() {
        return java.util.stream.Stream.of(
                org.junit.jupiter.params.provider.Arguments.of("local", "prod"),
                org.junit.jupiter.params.provider.Arguments.of("compose", "prod"));
    }
}

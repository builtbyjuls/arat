package com.builtbyjuls.arat.identity.security;

import jakarta.validation.constraints.NotBlank;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@ConfigurationProperties("arat.local.authentication")
@Validated
record LocalAuthenticationProperties(@NotBlank String bearerToken, Map<String, String> principalTokens) {

    LocalAuthenticationProperties {
        principalTokens = principalTokens == null ? Map.of() : Map.copyOf(principalTokens);
    }
}

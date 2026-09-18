package com.builtbyjuls.arat.identity.security;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@ConfigurationProperties("arat.local.authentication")
@Validated
record LocalAuthenticationProperties(@NotBlank String bearerToken) {
}

package com.builtbyjuls.arat.groups.infrastructure;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.util.Base64;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@ConfigurationProperties("arat.invitation.token")
@Validated
public record InvitationTokenProperties(
        @NotBlank String activeKeyId,
        @NotEmpty Map<String, String> keys) {

    public InvitationTokenProperties {
        keys = keys == null ? Map.of() : Map.copyOf(keys);
        if (activeKeyId == null || !keys.containsKey(activeKeyId)) {
            throw new IllegalArgumentException("the active invitation token key must be configured");
        }
        keys.forEach((keyId, value) -> {
            if (keyId == null || keyId.isBlank() || value == null || value.isBlank()) {
                throw new IllegalArgumentException("invitation token keys must have nonblank IDs and values");
            }
            try {
                if (Base64.getDecoder().decode(value).length < 32) {
                    throw new IllegalArgumentException("invitation token keys must contain at least 32 bytes");
                }
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException("invitation token keys must be Base64 encoded and at least 32 bytes", exception);
            }
        });
    }
}

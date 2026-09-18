package com.builtbyjuls.arat.identity.security;

import com.builtbyjuls.arat.identity.api.AuthenticatedActor;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;

final class LocalTokenAuthenticationProvider implements AuthenticationProvider {

    private final Map<String, byte[]> expectedBearerTokens;

    LocalTokenAuthenticationProvider(LocalAuthenticationProperties properties) {
        var ownerToken = token(properties, "owner");
        var memberToken = requiredToken(properties, "member");
        var outsiderToken = requiredToken(properties, "outsider");
        if (new HashSet<>(List.of(ownerToken, memberToken, outsiderToken)).size() != 3) {
            throw new IllegalStateException("Local principal tokens must be distinct.");
        }
        expectedBearerTokens = Map.of(
                "10000000-0000-4000-8000-000000000001", ownerToken.getBytes(StandardCharsets.UTF_8),
                "10000000-0000-4000-8000-000000000002", memberToken.getBytes(StandardCharsets.UTF_8),
                "10000000-0000-4000-8000-000000000003", outsiderToken.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public Authentication authenticate(Authentication authentication) {
        var suppliedBearerToken = ((String) authentication.getCredentials()).getBytes(StandardCharsets.UTF_8);
        var accountId = expectedBearerTokens.entrySet().stream()
                .filter(entry -> MessageDigest.isEqual(entry.getValue(), suppliedBearerToken))
                .map(Map.Entry::getKey)
                .findFirst()
                .orElseThrow(() -> new BadCredentialsException("Invalid bearer token."));
        var actor = new AuthenticatedActor(UUID.fromString(accountId), java.util.Set.of());
        return new UsernamePasswordAuthenticationToken(actor, null, List.of());
    }

    private static String token(LocalAuthenticationProperties properties, String principal) {
        var configuredToken = properties.principalTokens().getOrDefault(principal, properties.bearerToken());
        if (configuredToken.isBlank()) {
            throw new BadCredentialsException("Invalid bearer token.");
        }
        return configuredToken;
    }

    private static String requiredToken(LocalAuthenticationProperties properties, String principal) {
        var configuredToken = properties.principalTokens().get(principal);
        if (configuredToken == null || configuredToken.isBlank()) {
            throw new IllegalStateException("Local principal token is required for " + principal + ".");
        }
        return configuredToken;
    }

    @Override
    public boolean supports(Class<?> authentication) {
        return LocalBearerTokenAuthentication.class.isAssignableFrom(authentication);
    }
}

package com.builtbyjuls.arat.identity.security;

import com.builtbyjuls.arat.identity.api.AuthenticatedActor;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.UUID;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;

final class LocalTokenAuthenticationProvider implements AuthenticationProvider {

    private static final UUID LOCAL_OWNER_ACCOUNT_ID = UUID.fromString("10000000-0000-4000-8000-000000000001");

    private final byte[] expectedBearerToken;

    LocalTokenAuthenticationProvider(LocalAuthenticationProperties properties) {
        expectedBearerToken = properties.bearerToken().getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public Authentication authenticate(Authentication authentication) {
        var suppliedBearerToken = ((String) authentication.getCredentials()).getBytes(StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(expectedBearerToken, suppliedBearerToken)) {
            throw new BadCredentialsException("Invalid bearer token.");
        }
        var actor = new AuthenticatedActor(LOCAL_OWNER_ACCOUNT_ID, java.util.Set.of());
        return new UsernamePasswordAuthenticationToken(actor, null, List.of());
    }

    @Override
    public boolean supports(Class<?> authentication) {
        return LocalBearerTokenAuthentication.class.isAssignableFrom(authentication);
    }
}

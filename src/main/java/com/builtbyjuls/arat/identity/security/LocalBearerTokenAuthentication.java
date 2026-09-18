package com.builtbyjuls.arat.identity.security;

import java.util.List;
import org.springframework.security.authentication.AbstractAuthenticationToken;

final class LocalBearerTokenAuthentication extends AbstractAuthenticationToken {

    private final String bearerToken;

    LocalBearerTokenAuthentication(String bearerToken) {
        super(List.of());
        this.bearerToken = bearerToken;
        setAuthenticated(false);
    }

    @Override
    public Object getCredentials() {
        return bearerToken;
    }

    @Override
    public Object getPrincipal() {
        return null;
    }
}

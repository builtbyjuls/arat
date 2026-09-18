package com.builtbyjuls.arat.identity.security;

import com.builtbyjuls.arat.identity.api.AuthenticatedActor;
import com.builtbyjuls.arat.identity.api.CurrentActor;
import com.builtbyjuls.arat.identity.api.UnauthenticatedActorException;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

@Component
final class SecurityContextCurrentActor implements CurrentActor {

    @Override
    public AuthenticatedActor requireAuthenticatedActor() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null
                || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken
                || !(authentication.getPrincipal() instanceof AuthenticatedActor actor)) {
            throw new UnauthenticatedActorException();
        }
        return actor;
    }
}

package com.builtbyjuls.arat.identity.testing;

import com.builtbyjuls.arat.identity.api.AuthenticatedActor;
import java.util.Arrays;
import java.util.UUID;
import java.util.stream.Stream;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.test.context.support.WithSecurityContextFactory;

public final class WithAratActorSecurityContextFactory implements WithSecurityContextFactory<WithAratActor> {

    @Override
    public org.springframework.security.core.context.SecurityContext createSecurityContext(WithAratActor annotation) {
        var platformRoles = Stream.concat(
                        annotation.account().platformRoles().stream(), Arrays.stream(annotation.platformRoles()))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        var actor = new AuthenticatedActor(
                annotation.accountId().isBlank()
                        ? annotation.account().account().accountId()
                        : UUID.fromString(annotation.accountId()),
                platformRoles);
        var authorities = platformRoles.stream()
                .map(role -> new SimpleGrantedAuthority(role.name()))
                .toList();
        var authentication = new TestingAuthenticationToken(actor, null, authorities);
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        return context;
    }
}

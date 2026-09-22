package com.builtbyjuls.arat.identity.testing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.builtbyjuls.arat.identity.api.PlatformRole;
import org.junit.jupiter.api.Test;

class WithAratActorSecurityContextFactoryTest {

    @Test
    void derivesPlatformRoleFromTheOperatorFixtureOnly() {
        var operatorContext = contextFor(AratAccountFixture.OPERATOR);
        var providerContext = contextFor(AratAccountFixture.PROVIDER);

        assertThat(operatorContext.getAuthentication().getAuthorities())
                .extracting(Object::toString)
                .containsExactly(PlatformRole.PLATFORM_OPERATOR.name());
        assertThat(providerContext.getAuthentication().getAuthorities()).isEmpty();
    }

    private static org.springframework.security.core.context.SecurityContext contextFor(AratAccountFixture account) {
        var annotation = mock(WithAratActor.class);
        when(annotation.account()).thenReturn(account);
        when(annotation.accountId()).thenReturn("");
        when(annotation.platformRoles()).thenReturn(new PlatformRole[0]);
        return new WithAratActorSecurityContextFactory().createSecurityContext(annotation);
    }
}

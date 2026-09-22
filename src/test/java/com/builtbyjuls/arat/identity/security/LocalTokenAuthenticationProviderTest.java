package com.builtbyjuls.arat.identity.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.builtbyjuls.arat.identity.api.AuthenticatedActor;
import com.builtbyjuls.arat.identity.api.PlatformRole;
import java.util.Map;
import org.junit.jupiter.api.Test;

class LocalTokenAuthenticationProviderTest {

    @Test
    void usesTheLegacyBearerTokenForTheOwnerWhenNoOwnerOverrideExists() {
        var provider = new LocalTokenAuthenticationProvider(properties("owner-override"));

        var authentication = provider.authenticate(new LocalBearerTokenAuthentication("owner-override"));

        assertThat(((AuthenticatedActor) authentication.getPrincipal()).accountId())
                .isEqualTo(LocalAccountFixtures.OWNER.accountId());
    }

    @Test
    void assignsThePlatformOperatorRoleOnlyToTheOperatorToken() {
        var provider = new LocalTokenAuthenticationProvider(properties("owner-override"));

        var operatorAuthentication = provider.authenticate(new LocalBearerTokenAuthentication("operator-token"));
        var providerAuthentication = provider.authenticate(new LocalBearerTokenAuthentication("provider-token"));

        assertThat(((AuthenticatedActor) operatorAuthentication.getPrincipal()).platformRoles())
                .containsExactly(PlatformRole.PLATFORM_OPERATOR);
        assertThat(((AuthenticatedActor) providerAuthentication.getPrincipal()).platformRoles()).isEmpty();
    }

    @Test
    void rejectsDuplicatePrincipalTokensAtStartup() {
        assertThatThrownBy(() -> new LocalTokenAuthenticationProvider(
                        new LocalAuthenticationProperties("same-token", Map.of(
                                "member", "same-token",
                                "outsider", "outsider-token",
                                "provider", "provider-token",
                                "operator", "operator-token"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Local principal tokens must be distinct.");
    }

    @Test
    void rejectsMissingProviderOrOperatorTokensAtStartup() {
        assertThatThrownBy(() -> new LocalTokenAuthenticationProvider(
                        new LocalAuthenticationProperties("owner-token", Map.of(
                                "member", "member-token",
                                "outsider", "outsider-token",
                                "operator", "operator-token"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Local principal token is required for provider.");
    }

    private static LocalAuthenticationProperties properties(String ownerToken) {
        return new LocalAuthenticationProperties(ownerToken, Map.of(
                "member", "member-token",
                "outsider", "outsider-token",
                "provider", "provider-token",
                "operator", "operator-token"));
    }
}

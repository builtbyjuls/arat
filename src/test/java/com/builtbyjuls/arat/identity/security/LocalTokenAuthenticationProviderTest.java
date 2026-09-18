package com.builtbyjuls.arat.identity.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.builtbyjuls.arat.identity.api.AuthenticatedActor;
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
    void rejectsDuplicatePrincipalTokensAtStartup() {
        assertThatThrownBy(() -> new LocalTokenAuthenticationProvider(
                        new LocalAuthenticationProperties("same-token", Map.of(
                                "member", "same-token",
                                "outsider", "outsider-token"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Local principal tokens must be distinct.");
    }

    @Test
    void rejectsMissingMemberOrOutsiderTokensAtStartup() {
        assertThatThrownBy(() -> new LocalTokenAuthenticationProvider(
                        new LocalAuthenticationProperties("owner-token", Map.of("member", "member-token"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Local principal token is required for outsider.");
    }

    private static LocalAuthenticationProperties properties(String ownerToken) {
        return new LocalAuthenticationProperties(ownerToken, Map.of(
                "member", "member-token",
                "outsider", "outsider-token"));
    }
}

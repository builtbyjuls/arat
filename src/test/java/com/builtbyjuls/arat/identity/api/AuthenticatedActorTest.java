package com.builtbyjuls.arat.identity.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AuthenticatedActorTest {

    private static final UUID ACCOUNT_ID = UUID.fromString("10000000-0000-4000-8000-000000000001");

    @Test
    void rejectsNullAccountId() {
        assertThatThrownBy(() -> new AuthenticatedActor(null, Set.of()))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("accountId must not be null");
    }

    @Test
    void rejectsNullPlatformRoles() {
        assertThatThrownBy(() -> new AuthenticatedActor(ACCOUNT_ID, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("platformRoles must not be null");
    }

    @Test
    void defensivelyCopiesPlatformRoles() {
        var roles = new HashSet<PlatformRole>();
        roles.add(PlatformRole.PLATFORM_OPERATOR);

        var actor = new AuthenticatedActor(ACCOUNT_ID, roles);
        roles.clear();

        assertThat(actor.accountId()).isEqualTo(ACCOUNT_ID);
        assertThat(actor.platformRoles()).containsExactly(PlatformRole.PLATFORM_OPERATOR);
        assertThatThrownBy(() -> actor.platformRoles().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }
}

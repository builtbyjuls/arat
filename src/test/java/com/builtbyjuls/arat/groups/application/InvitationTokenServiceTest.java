package com.builtbyjuls.arat.groups.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.builtbyjuls.arat.groups.infrastructure.InvitationTokenProperties;
import com.builtbyjuls.arat.groups.infrastructure.InvitationTokenService;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class InvitationTokenServiceTest {

    private static final String KEY = "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=";

    @Test
    void derivesTheFrozenCanonicalUnpaddedBase64urlTokenAndHashesRawHmacBytes() {
        var service = new InvitationTokenService(new InvitationTokenProperties("test", Map.of("test", KEY)));
        var inviteId = UUID.fromString("10000000-0000-4000-8000-000000000001");
        var groupId = UUID.fromString("20000000-0000-4000-8000-000000000001");
        var inviteeId = UUID.fromString("30000000-0000-4000-8000-000000000001");
        var nonce = new byte[32];
        for (var index = 0; index < nonce.length; index++) {
            nonce[index] = (byte) index;
        }

        var token = service.tokenFor(inviteId, groupId, inviteeId, nonce, "test");

        assertThat(token).isEqualTo("9sxCzhefdEx6zznFs_P3QzyqVYe0Tlj59oPpL4foEWA");
        assertThat(token).doesNotContain("=");
        assertThat(service.digest(token)).isEqualTo("55ac50c2d4fc4fc7e8bda0dbc063309e896b2bf397db2ec3e2a8f3721945c014");
    }

    @Test
    void generatesExactlyThirtyTwoRandomNonceBytes() {
        var service = new InvitationTokenService(new InvitationTokenProperties("test", Map.of("test", KEY)));

        assertThat(service.newNonce()).hasSize(32);
    }

    @Test
    void rejectsMissingAndTooShortNonlocalKeyConfiguration() {
        assertThatThrownBy(() -> new InvitationTokenProperties("missing", Map.of("other", KEY)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new InvitationTokenProperties("short", Map.of("short", "c2hvcnQ=")))
                .isInstanceOf(IllegalArgumentException.class);
    }
}

package com.builtbyjuls.arat.groups.infrastructure;

import com.builtbyjuls.arat.groups.domain.Invitation;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

@Component
public class InvitationTokenService {

    private static final SecureRandom RANDOM = new SecureRandom();
    private final InvitationTokenProperties properties;

    public InvitationTokenService(InvitationTokenProperties properties) {
        this.properties = properties;
    }

    public String activeKeyId() {
        return properties.activeKeyId();
    }

    public byte[] newNonce() {
        var nonce = new byte[32];
        RANDOM.nextBytes(nonce);
        return nonce;
    }

    public String tokenFor(UUID inviteId, UUID groupId, UUID inviteeAccountId, byte[] nonce, String keyId) {
        try {
            var key = properties.keys().get(keyId);
            if (key == null) {
                throw new IllegalStateException("invitation token key is unavailable");
            }
            var mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(Base64.getDecoder().decode(key), "HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(canonicalInput(
                    inviteId, groupId, inviteeAccountId, nonce)));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("invitation token cannot be derived", exception);
        }
    }

    public String digest(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Base64.getUrlDecoder().decode(token)));
        } catch (GeneralSecurityException | IllegalArgumentException exception) {
            throw new IllegalArgumentException("invitation token is invalid", exception);
        }
    }

    private byte[] canonicalInput(UUID inviteId, UUID groupId, UUID inviteeAccountId, byte[] nonce) {
        var nonceValue = Base64.getUrlEncoder().withoutPadding().encodeToString(nonce);
        var input = String.join("\n",
                "arat-invite:v1",
                inviteId.toString().toLowerCase(Locale.ROOT),
                groupId.toString().toLowerCase(Locale.ROOT),
                inviteeAccountId.toString().toLowerCase(Locale.ROOT),
                nonceValue);
        return input.getBytes(StandardCharsets.UTF_8);
    }
}

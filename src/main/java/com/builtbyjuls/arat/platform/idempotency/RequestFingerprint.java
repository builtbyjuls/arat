package com.builtbyjuls.arat.platform.idempotency;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

public record RequestFingerprint(String value) {

    public RequestFingerprint {
        Objects.requireNonNull(value, "value must not be null");
        if (!value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("value must be a lowercase SHA-256 fingerprint");
        }
    }

    public static RequestFingerprint fromCanonicalFields(Object canonicalFields) {
        return fromCanonicalJson(CanonicalCommandFields.toJson(canonicalFields));
    }

    public static RequestFingerprint fromCanonicalJson(String canonicalJson) {
        Objects.requireNonNull(canonicalJson, "canonicalJson must not be null");
        try {
            var digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonicalJson.getBytes(StandardCharsets.UTF_8));
            return new RequestFingerprint(HexFormat.of().formatHex(digest));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 must be available", exception);
        }
    }
}

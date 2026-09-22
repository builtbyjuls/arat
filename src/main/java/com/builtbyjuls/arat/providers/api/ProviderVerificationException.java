package com.builtbyjuls.arat.providers.api;

public class ProviderVerificationException extends RuntimeException {

    public enum Reason {
        PRIVATE_RESOURCE_NOT_FOUND,
        FORBIDDEN_ROLE,
        INVALID_PROVIDER_STATE
    }

    private final Reason reason;

    public ProviderVerificationException(Reason reason) {
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}

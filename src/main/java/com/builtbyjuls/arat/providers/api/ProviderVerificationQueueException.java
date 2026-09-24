package com.builtbyjuls.arat.providers.api;

public class ProviderVerificationQueueException extends RuntimeException {

    private final Reason reason;

    public ProviderVerificationQueueException(Reason reason) {
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }

    public enum Reason {
        FORBIDDEN_PLATFORM_ROLE,
        INVALID_CURSOR
    }
}

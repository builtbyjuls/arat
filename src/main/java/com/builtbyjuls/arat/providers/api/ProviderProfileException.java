package com.builtbyjuls.arat.providers.api;

public class ProviderProfileException extends RuntimeException {

    public enum Reason {
        PRIVATE_RESOURCE_NOT_FOUND,
        FORBIDDEN_ROLE,
        PRECONDITION_FAILED
    }

    private final Reason reason;

    public ProviderProfileException(Reason reason) {
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}

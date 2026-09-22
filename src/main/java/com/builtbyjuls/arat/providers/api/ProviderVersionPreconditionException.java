package com.builtbyjuls.arat.providers.api;

public class ProviderVersionPreconditionException extends RuntimeException {

    public enum Reason {
        PRECONDITION_REQUIRED,
        INVALID_PRECONDITION
    }

    private final Reason reason;

    public ProviderVersionPreconditionException(Reason reason) {
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}

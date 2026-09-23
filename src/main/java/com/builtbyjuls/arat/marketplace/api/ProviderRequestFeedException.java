package com.builtbyjuls.arat.marketplace.api;

public class ProviderRequestFeedException extends RuntimeException {

    public enum Reason {
        PRIVATE_RESOURCE_NOT_FOUND,
        INVALID_CURSOR
    }

    private final Reason reason;

    public ProviderRequestFeedException(Reason reason) {
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}

package com.builtbyjuls.arat.providers.api;

public class ProviderRestorationException extends RuntimeException {

    public enum Reason {
        FORBIDDEN_PLATFORM_ROLE,
        PRIVATE_RESOURCE_NOT_FOUND,
        INVALID_PROVIDER_STATE
    }

    private final Reason reason;

    public ProviderRestorationException(Reason reason) {
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}

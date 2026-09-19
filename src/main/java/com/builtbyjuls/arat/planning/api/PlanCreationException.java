package com.builtbyjuls.arat.planning.api;

public class PlanCreationException extends RuntimeException {

    public enum Reason {
        PRIVATE_RESOURCE_NOT_FOUND
    }

    private final Reason reason;

    public PlanCreationException(Reason reason) {
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}

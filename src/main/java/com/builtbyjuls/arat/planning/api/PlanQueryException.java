package com.builtbyjuls.arat.planning.api;

public class PlanQueryException extends RuntimeException {

    public enum Reason {
        PRIVATE_RESOURCE_NOT_FOUND,
        INVALID_CURSOR
    }

    private final Reason reason;

    public PlanQueryException(Reason reason) {
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}

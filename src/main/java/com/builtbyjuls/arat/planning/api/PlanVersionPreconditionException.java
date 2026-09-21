package com.builtbyjuls.arat.planning.api;

public class PlanVersionPreconditionException extends RuntimeException {

    public enum Reason {
        PRECONDITION_REQUIRED,
        INVALID_PRECONDITION
    }

    private final Reason reason;

    public PlanVersionPreconditionException(Reason reason) {
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}

package com.builtbyjuls.arat.planning.api;

public class PreferencePreconditionException extends RuntimeException {

    public enum Reason {
        PRECONDITION_REQUIRED,
        INVALID_PRECONDITION
    }

    private final Reason reason;

    public PreferencePreconditionException(Reason reason) {
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}

package com.builtbyjuls.arat.groups.api;

public class GroupVersionPreconditionException extends RuntimeException {

    public enum Reason {
        PRECONDITION_REQUIRED,
        INVALID_PRECONDITION
    }

    private final Reason reason;

    public GroupVersionPreconditionException(Reason reason) {
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}

package com.builtbyjuls.arat.marketplace.api;

public class RequestClosureException extends RuntimeException {

    public enum Reason {
        PRIVATE_RESOURCE_NOT_FOUND,
        FORBIDDEN_ROLE,
        PRECONDITION_FAILED,
        INVALID_REQUEST_STATE
    }

    private final Reason reason;

    public RequestClosureException(Reason reason) {
        super(reason.name());
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}

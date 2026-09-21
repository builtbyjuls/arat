package com.builtbyjuls.arat.planning.api;

public class PlanCancellationException extends RuntimeException {

    public enum Reason {
        PRIVATE_RESOURCE_NOT_FOUND,
        FORBIDDEN_ROLE,
        PRECONDITION_FAILED,
        INVALID_PLAN_STATE
    }

    private final Reason reason;

    public PlanCancellationException(Reason reason) {
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}

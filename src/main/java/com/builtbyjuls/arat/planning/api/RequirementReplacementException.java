package com.builtbyjuls.arat.planning.api;

public class RequirementReplacementException extends RuntimeException {

    public enum Reason {
        PRIVATE_RESOURCE_NOT_FOUND,
        FORBIDDEN_ROLE,
        PRECONDITION_FAILED,
        INVALID_PLAN_STATE,
        VALIDATION_FAILED
    }

    private final Reason reason;

    public RequirementReplacementException(Reason reason) {
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}

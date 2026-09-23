package com.builtbyjuls.arat.planning.api;

public class PlanningRequestTransitionException extends RuntimeException {

    public enum Reason {
        PRIVATE_RESOURCE_NOT_FOUND,
        PRECONDITION_FAILED,
        INVALID_PLAN_STATE,
        INVALID_REQUEST_STATE,
        FINALIZATION_MISMATCH,
        DEADLINE_ELAPSED
    }

    private final Reason reason;

    public PlanningRequestTransitionException(Reason reason) {
        super(reason.name());
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}

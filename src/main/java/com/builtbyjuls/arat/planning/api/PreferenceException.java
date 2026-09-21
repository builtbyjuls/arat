package com.builtbyjuls.arat.planning.api;

public class PreferenceException extends RuntimeException {

    public enum Reason {
        PRIVATE_RESOURCE_NOT_FOUND,
        PREFERENCE_NOT_FOUND,
        PRECONDITION_FAILED,
        REQUIREMENT_VERSION_CHANGED,
        INVALID_PLAN_STATE,
        VALIDATION_FAILED
    }

    private final Reason reason;

    public PreferenceException(Reason reason) {
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}

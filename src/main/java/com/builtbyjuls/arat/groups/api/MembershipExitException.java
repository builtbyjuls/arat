package com.builtbyjuls.arat.groups.api;

public class MembershipExitException extends RuntimeException {

    public enum Reason {
        PRIVATE_RESOURCE_NOT_FOUND,
        FORBIDDEN_ROLE,
        VALIDATION_FAILED,
        FINAL_ORGANIZER_REQUIRED
    }

    private final Reason reason;

    public MembershipExitException(Reason reason) {
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}

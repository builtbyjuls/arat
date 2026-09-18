package com.builtbyjuls.arat.groups.api;

public class InvitationException extends RuntimeException {

    public enum Reason {
        PRIVATE_RESOURCE_NOT_FOUND,
        FORBIDDEN_ROLE,
        VALIDATION_FAILED,
        ALREADY_MEMBER,
        INVITATION_ALREADY_PENDING,
        INVITATION_UNAVAILABLE
    }

    private final Reason reason;

    public InvitationException(Reason reason) {
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}

package com.builtbyjuls.arat.groups.api;

public class OrganizerTransferException extends RuntimeException {

    public enum Reason {
        PRIVATE_RESOURCE_NOT_FOUND,
        FORBIDDEN_ROLE,
        VALIDATION_FAILED,
        ALREADY_ORGANIZER,
        PRECONDITION_FAILED
    }

    private final Reason reason;

    public OrganizerTransferException(Reason reason) {
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}

package com.builtbyjuls.arat.marketplace.api;

public class RequestPublicationException extends RuntimeException {

    public enum Reason {
        PRIVATE_RESOURCE_NOT_FOUND,
        FORBIDDEN_ROLE,
        PRECONDITION_FAILED,
        INVALID_PLAN_STATE,
        FINALIZATION_VERSION_CHANGED,
        NO_ELIGIBLE_PROVIDERS,
        RECIPIENT_LIMIT_EXCEEDED,
        REQUEST_DEADLINE_EXPIRED
    }

    private final Reason reason;

    public RequestPublicationException(Reason reason) {
        super(reason.name());
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}

package com.builtbyjuls.arat.identity.api;

public final class UnauthenticatedActorException extends RuntimeException {

    public UnauthenticatedActorException() {
        super("An authenticated actor is required.");
    }
}

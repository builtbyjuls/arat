package com.builtbyjuls.arat.platform.idempotency;

public class IdempotencyKeyReusedException extends RuntimeException {

    public IdempotencyKeyReusedException() {
        super("idempotency key was reused with a different request");
    }
}

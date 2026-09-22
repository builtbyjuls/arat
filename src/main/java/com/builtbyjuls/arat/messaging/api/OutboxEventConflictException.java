package com.builtbyjuls.arat.messaging.api;

/**
 * A business key already identifies different immutable event content.
 */
public class OutboxEventConflictException extends IllegalStateException {

    public OutboxEventConflictException(String businessKey) {
        super("outbox business key is already associated with different event content: " + businessKey);
    }
}

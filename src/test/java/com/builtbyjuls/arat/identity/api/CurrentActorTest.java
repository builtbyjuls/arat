package com.builtbyjuls.arat.identity.api;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class CurrentActorTest {

    @Test
    void exposesAStableFailureForUnauthenticatedAdapters() {
        CurrentActor currentActor = () -> {
            throw new UnauthenticatedActorException();
        };

        assertThatThrownBy(currentActor::requireAuthenticatedActor)
                .isInstanceOf(UnauthenticatedActorException.class)
                .hasMessage("An authenticated actor is required.");
    }
}

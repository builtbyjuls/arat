package com.builtbyjuls.arat.platform.idempotency;

public sealed interface ClaimResult permits ClaimResult.Claimed, ClaimResult.Replay,
        ClaimResult.ConflictingFingerprint, ClaimResult.InProgress {

    record Claimed() implements ClaimResult {
    }

    record Replay(CompletedIdempotencyResponse response) implements ClaimResult {
    }

    record ConflictingFingerprint() implements ClaimResult {
    }

    record InProgress() implements ClaimResult {
    }
}

package com.guodi.pragent.harness.restore;

/** Recovery decision for an existing, unfinished review run. */
public record RestoreResult(
        Status status,
        int nextRoundNumber,
        String externalReviewId) {

    public enum Status {
        RESUME,
        PUBLISHED,
        PENDING_VERIFICATION
    }
}

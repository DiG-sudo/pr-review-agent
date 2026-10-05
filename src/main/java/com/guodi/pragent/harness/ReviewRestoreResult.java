package com.guodi.pragent.harness;

/** Recovery decision for an existing, unfinished review run. */
public record ReviewRestoreResult(
        Status status,
        int nextRoundNumber,
        String externalReviewId) {

    public enum Status {
        RESUME,
        PUBLISHED,
        PENDING_VERIFICATION
    }
}

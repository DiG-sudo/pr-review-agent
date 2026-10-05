package com.guodi.pragent.persistence.reviewrun;

import java.util.List;

import com.guodi.pragent.reviewer.Finding;

/** Stable JSON shape stored in {@code review_run.review_state_json}. */
public record StoredReviewState(boolean published,List<Finding> findings) {

    public StoredReviewState {
        findings = List.copyOf(findings);
    }


}

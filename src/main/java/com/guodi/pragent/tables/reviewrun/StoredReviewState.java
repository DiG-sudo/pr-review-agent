package com.guodi.pragent.tables.reviewrun;

import java.util.List;

import com.guodi.pragent.reviewer.state.Finding;

/** Stable JSON shape stored in {@code review_run.review_state_json}. */
public record StoredReviewState(boolean published,List<Finding> findings) {

    public StoredReviewState {
        findings = List.copyOf(findings);
    }


}

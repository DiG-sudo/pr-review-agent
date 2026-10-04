package com.guodi.pragent.reviewer;

import com.guodi.pragent.reviewer.github.ReviewRequest;

/** The one application entry point: beforeRun, Runtime.run, then terminal cleanup. */
@FunctionalInterface
public interface ReviewerAgent {

    void call(ReviewRequest request);
}

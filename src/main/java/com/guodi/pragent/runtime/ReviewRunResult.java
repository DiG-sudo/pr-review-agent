package com.guodi.pragent.runtime;

import java.util.Objects;

/** 整个 PR 审查任务的收口结果。 */
public record ReviewRunResult(ReviewStatus status) {

    public ReviewRunResult {
        Objects.requireNonNull(status, "status");
        if (status == ReviewStatus.PENDING || status == ReviewStatus.RUNNING) {
            throw new IllegalArgumentException("审查任务尚未收口: " + status);
        }
    }
}

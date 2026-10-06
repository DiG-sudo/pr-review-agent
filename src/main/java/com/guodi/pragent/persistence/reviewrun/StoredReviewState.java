package com.guodi.pragent.persistence.reviewrun;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.guodi.pragent.reviewer.Finding;

/** Finding 快照；发布状态只保存在 review_run.status。兼容旧快照的 published 字段。 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record StoredReviewState(List<Finding> findings) {

    public StoredReviewState {
        findings = List.copyOf(findings);
    }
}

package com.guodi.pragent.runtime;

/** 任务的唯一状态约定，与 review_run.status 的存储值一致。 */
public enum ReviewStatus {
    PENDING,
    RUNNING,
    PUBLICATION_READY,
    PUBLISHED,
    FAILED
}

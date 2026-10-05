package com.guodi.pragent.reviewer;

/** One structured issue recorded during a PR review. */
public record Finding(
        String id,
        String severity,
        String category,
        String file,
        int startLine,
        String description,
        String suggestion,
        String status,
        String note) {}

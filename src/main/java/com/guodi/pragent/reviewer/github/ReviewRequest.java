package com.guodi.pragent.reviewer.github;

public  record ReviewRequest(
    String threadId,
    String repository,      // owner/repo
    int pullRequestNumber,
    String headSha,
    String baseSha,
    String fixtureId        // 当前本地阶段用于定位 fixture
) {}

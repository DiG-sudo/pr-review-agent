package com.guodi.pragent.harness.restore;

public  record ReviewRequest(
    String threadId,
    String repository,      // owner/repo
    int pullRequestNumber,
    String headSha,
    String fixtureId        // 当前本地阶段用于定位 fixture
) {}

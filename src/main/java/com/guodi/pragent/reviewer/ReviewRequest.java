package com.guodi.pragent.reviewer;

// repository: owner/repo
// fixtureId: 当前本地阶段用于定位 fixture
public record ReviewRequest(String threadId, String repository, int pullRequestNumber, String headSha, String baseSha, String fixtureId) {}

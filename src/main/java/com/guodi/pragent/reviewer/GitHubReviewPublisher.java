package com.guodi.pragent.reviewer;

import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.databind.JsonNode;

/** 发布一条已提交的汇总 Review；网络或响应异常交给 Harness 处理。 */
@Component
public final class GitHubReviewPublisher {
    private final RestClient github;

    public GitHubReviewPublisher(RestClient.Builder builder, @Value("${GITHUB_TOKEN:}") String token) {
        github = builder.baseUrl("https://api.github.com")
                .defaultHeader("Accept", "application/vnd.github+json")
                .defaultHeader("X-GitHub-Api-Version", "2022-11-28")
                .defaultHeaders(headers -> {
                    if (!token.isBlank()) {
                        headers.setBearerAuth(token);
                    }
                }).build();
    }

    public long publish(String repository, int pullRequestNumber, String headSha,
            String publicationKey, String body) {
        String markedBody = body + "\n\n<!-- pr-review-agent:publication-key=" + publicationKey + " -->";
        String[] repo = repository.split("/", 2);
        JsonNode response = github.post()
                .uri("/repos/{owner}/{repo}/pulls/{number}/reviews", repo[0], repo[1], pullRequestNumber)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("commit_id", headSha, "body", markedBody, "event", "COMMENT"))
                .retrieve().body(JsonNode.class);
        if (response == null || response.path("id").asLong() <= 0
                || response.path("submitted_at").isMissingNode() || response.path("submitted_at").isNull()) {
            throw new IllegalStateException("GitHub 未返回已提交的 Review");
        }
        return response.path("id").asLong();
    }
}

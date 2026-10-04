package com.guodi.pragent.reviewer.github;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Optional;
import java.util.Set;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Validates and translates a GitHub pull_request delivery into a review request. */
public final class GitHubWebhookEvent {

    private static final Set<String> REVIEW_ACTIONS = Set.of("opened", "reopened", "synchronize");
    private static final String SHA_PATTERN = "[0-9a-fA-F]{40}";
    private static final String REPOSITORY_PATTERN = "[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+";

    private final ObjectMapper json;
    private final String secret;

    public GitHubWebhookEvent(ObjectMapper json, String secret) {
        this.json = json;
        if (secret == null || secret.isBlank()) {
            throw new IllegalArgumentException("GITHUB_WEBHOOK_SECRET must be configured");
        }
        this.secret = secret;
    }

    public Optional<ReviewRequest> parse(byte[] body, String event, String signature) throws IOException {
        verify(body, signature);
        if (!"pull_request".equals(event)) {
            return Optional.empty();
        }
        JsonNode payload = json.readTree(body);
        if (payload == null || !payload.isObject()) {
            throw new IllegalArgumentException("invalid pull_request webhook payload");
        }
        if (!REVIEW_ACTIONS.contains(payload.path("action").asText())) {
            return Optional.empty();
        }
        String repository = payload.path("repository").path("full_name").asText();
        int number = payload.path("number").asInt();
        String headSha = payload.path("pull_request").path("head").path("sha").asText();
        String baseSha = payload.path("pull_request").path("base").path("sha").asText();
        if (!repository.matches(REPOSITORY_PATTERN) || number <= 0
                || !headSha.matches(SHA_PATTERN) || !baseSha.matches(SHA_PATTERN)) {
            throw new IllegalArgumentException("invalid pull_request webhook payload");
        }
        String threadId = "github:" + repository + "#" + number;
        return Optional.of(new ReviewRequest(threadId, repository, number, headSha, baseSha, null));
    }

    private void verify(byte[] body, String signature) {
        if (body == null || signature == null || !signature.matches("sha256=[0-9a-fA-F]{64}")) {
            throw new SecurityException("invalid GitHub webhook signature");
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] expected = mac.doFinal(body);
            byte[] actual = HexFormat.of().parseHex(signature.substring("sha256=".length()));
            if (!MessageDigest.isEqual(expected, actual)) {
                throw new SecurityException("invalid GitHub webhook signature");
            }
        } catch (java.security.GeneralSecurityException error) {
            throw new IllegalStateException("cannot verify GitHub webhook signature", error);
        }
    }
}

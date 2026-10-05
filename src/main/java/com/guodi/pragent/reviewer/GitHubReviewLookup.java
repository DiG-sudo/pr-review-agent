package com.guodi.pragent.reviewer;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.OptionalLong;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Looks up an already submitted GitHub PR review by the marker in its body. */
@Component
public final class GitHubReviewLookup {

    private static final int PAGE_SIZE = 100;
    private final Sender sender;
    private final ObjectMapper objectMapper;
    private final URI apiBase;
    private final String token;

    @FunctionalInterface
    interface Sender {
        Response send(HttpRequest request) throws IOException;
    }

    record Response(int statusCode, String body) {}

    @Autowired
    public GitHubReviewLookup(ObjectMapper objectMapper,
            @Value("${GITHUB_TOKEN:}") String token) {
        this(httpSender(), objectMapper, URI.create("https://api.github.com"), token);
    }

    GitHubReviewLookup(Sender sender, ObjectMapper objectMapper, URI apiBase, String token) {
        this.sender = sender;
        this.objectMapper = objectMapper;
        this.apiBase = apiBase;
        this.token = token;
    }

    /** Empty means every page was read successfully and no matching review exists. */
    public OptionalLong findPublished(
            String repository, int pullRequestNumber, String headSha, String publicationKey)
            throws IOException {
        if (repository == null || !repository.matches("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+")) {
            throw new IllegalArgumentException("repository must be owner/name");
        }
        if (pullRequestNumber <= 0 || headSha == null || !headSha.matches("[0-9a-fA-F]{40}")) {
            throw new IllegalArgumentException("invalid PR number or head SHA");
        }
        if (publicationKey == null || !publicationKey.matches("[A-Za-z0-9._:-]+")) {
            throw new IllegalArgumentException("invalid publication key");
        }

        String marker = "<!-- pr-review-agent:publication-key=" + publicationKey + " -->";
        for (int page = 1; ; page++) {
            URI uri = apiBase.resolve("/repos/" + repository + "/pulls/"
                    + pullRequestNumber + "/reviews?per_page=" + PAGE_SIZE + "&page=" + page);
            Response response = sender.send(request(uri));
            if (response.statusCode() != 200) {
                throw new IOException("GitHub review lookup returned HTTP " + response.statusCode());
            }
            JsonNode reviews = objectMapper.readTree(response.body());
            if (!reviews.isArray()) {
                throw new IOException("GitHub review listing is not an array");
            }
            for (JsonNode review : reviews) {
                if (review.path("body").asText("").contains(marker)
                        && headSha.equalsIgnoreCase(review.path("commit_id").asText())
                        && !review.path("submitted_at").isMissingNode()
                        && !review.path("submitted_at").isNull()) {
                    long reviewId = review.path("id").asLong(0);
                    if (reviewId <= 0) {
                        throw new IOException("matching GitHub review has no ID");
                    }
                    return OptionalLong.of(reviewId);
                }
            }
            if (reviews.size() < PAGE_SIZE) {
                return OptionalLong.empty();
            }
        }
    }

    private HttpRequest request(URI uri) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(30))
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .GET();
        if (token != null && !token.isBlank()) {
            builder.header("Authorization", "Bearer " + token);
        }
        return builder.build();
    }

    private static Sender httpSender() {
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
        return request -> {
            try {
                HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
                return new Response(response.statusCode(), response.body());
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new IOException("GitHub review lookup interrupted", error);
            }
        };
    }
}

package com.guodi.pragent.reviewer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayDeque;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import com.fasterxml.jackson.databind.ObjectMapper;

class GitHubReviewLookupTest {

    private static final String SHA = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static final String REPO = "DiG-sudo/repository-analysis-agent";

    @Test
    void springSelectsTheProductionConstructor() {
        new ApplicationContextRunner()
                .withBean(ObjectMapper.class, ObjectMapper::new)
                .withUserConfiguration(GitHubReviewLookup.class)
                .run(context -> assertThat(context).hasSingleBean(GitHubReviewLookup.class));
    }

    @Test
    void findsOnlyTheSubmittedReviewForTheSameKeyAndCommit() throws Exception {
        String reviews = "[{\"id\":1,\"body\":\"<!-- pr-review-agent:publication-key=run-1 -->\","
                + "\"commit_id\":\"" + SHA + "\",\"submitted_at\":null},"
                + "{\"id\":42,\"body\":\"<!-- pr-review-agent:publication-key=run-1 -->\","
                + "\"commit_id\":\"" + SHA + "\","
                + "\"submitted_at\":\"2026-10-02T00:00:00Z\"}]";
        GitHubReviewLookup lookup = lookup(request -> {
            assertThat(request.uri().toString()).contains("/pulls/1/reviews?per_page=100&page=1");
            return new GitHubReviewLookup.Response(200, reviews);
        });

        assertThat(lookup.findPublished(REPO, 1, SHA, "run-1")).hasValue(42);
    }

    @Test
    void distinguishesNotFoundFromFailedLookup() throws Exception {
        ArrayDeque<GitHubReviewLookup.Response> responses = new ArrayDeque<>();
        responses.add(new GitHubReviewLookup.Response(200, "[]"));
        responses.add(new GitHubReviewLookup.Response(503, "unavailable"));
        GitHubReviewLookup lookup = lookup(request -> responses.remove());

        assertThat(lookup.findPublished(REPO, 1, SHA, "run-1")).isEmpty();
        assertThatThrownBy(() -> lookup.findPublished(REPO, 1, SHA, "run-1"))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("HTTP 503");
    }

    private static GitHubReviewLookup lookup(GitHubReviewLookup.Sender sender) {
        return new GitHubReviewLookup(sender, new ObjectMapper(),
                URI.create("https://api.github.com"), "");
    }
}

package com.guodi.pragent.reviewer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class GitHubReviewPublisherTest {
    @Test
    void submitsCommentForPinnedRevisionWithRecoveryMarkerAndReturnsRemoteId() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        GitHubReviewPublisher publisher = new GitHubReviewPublisher(builder, "test-token");
        server.expect(requestTo("https://api.github.com/repos/owner/repo/pulls/1/reviews"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer test-token"))
                .andExpect(jsonPath("$.commit_id").value("abc"))
                .andExpect(jsonPath("$.event").value("COMMENT"))
                .andExpect(jsonPath("$.body").value("review body\n\n<!-- pr-review-agent:publication-key=run-7 -->"))
                .andRespond(withSuccess("{\"id\":42,\"submitted_at\":\"2026-10-07T00:00:00Z\"}", MediaType.APPLICATION_JSON));
        assertThat(publisher.publish("owner/repo", 1, "abc", "run-7", "review body")).isEqualTo(42);
        server.verify();
    }

    @Test
    void rejectsPendingReviewAndPropagatesHttpFailures() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        GitHubReviewPublisher publisher = new GitHubReviewPublisher(builder, "");
        server.expect(anything()).andRespond(withSuccess("{\"id\":42,\"submitted_at\":null}", MediaType.APPLICATION_JSON));
        server.expect(anything()).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        assertThatThrownBy(() -> publisher.publish("owner/repo", 1, "abc", "run-7", "body"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> publisher.publish("owner/repo", 1, "abc", "run-7", "body"))
                .isInstanceOf(org.springframework.web.client.RestClientResponseException.class);
        server.verify();
    }
}

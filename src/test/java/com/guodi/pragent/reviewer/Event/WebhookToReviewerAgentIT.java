package com.guodi.pragent.reviewer.Event;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.guodi.pragent.reviewer.ReviewerAgent;
import com.guodi.pragent.reviewer.github.ReviewRequest;
import com.guodi.pragent.tables.outbox.OutboxEventEntity;
import com.guodi.pragent.tables.outbox.OutboxEventMapper;
import com.guodi.pragent.tables.reviewrun.ReviewRunEntity;
import com.guodi.pragent.tables.reviewrun.ReviewRunMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "GITHUB_WEBHOOK_SECRET=chain-test-secret",
        "spring.datasource.url=jdbc:mysql://127.0.0.1:3306/pr_review_agent_chain_test",
        "spring.datasource.username=root",
        "spring.datasource.password=${PR_REVIEW_DB_ROOT_PASSWORD:root_dev}",
        "spring.data.redis.database=15"
})
@Import(WebhookToReviewerAgentIT.TestAgentConfig.class)
class WebhookToReviewerAgentIT {

    @Autowired private TestRestTemplate http;
    @Autowired private ProbeAgent agent;
    @Autowired private ReviewRunMapper reviews;
    @Autowired private OutboxEventMapper outbox;

    @Test
    void signedWebhookReachesReviewerAgentThroughOutboxAndStream() throws Exception {
        int prNumber = 800_000_000 + (int) (System.nanoTime() % 100_000_000);
        String headSha = UUID.randomUUID().toString().replace("-", "") + "00000000";
        String baseSha = "b".repeat(40);
        String payload = "{\"action\":\"opened\",\"number\":" + prNumber
                + ",\"repository\":{\"full_name\":\"chain-test/repo\"},"
                + "\"pull_request\":{\"head\":{\"sha\":\"" + headSha + "\"},"
                + "\"base\":{\"sha\":\"" + baseSha + "\"}}}";

        HttpHeaders headers = new HttpHeaders();
        headers.set("X-GitHub-Event", "pull_request");
        headers.set("X-Hub-Signature-256", sign(payload));
        headers.set("Content-Type", "application/json");
        ResponseEntity<String> response = http.postForEntity("/github/webhook",
                new HttpEntity<>(payload.getBytes(StandardCharsets.UTF_8), headers), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(response.getBody()).isEqualTo("created");
        assertThat(agent.called.await(15, TimeUnit.SECONDS)).isTrue();
        assertThat(agent.request).isEqualTo(new ReviewRequest(
                "github:chain-test/repo#" + prNumber, "chain-test/repo", prNumber,
                headSha, baseSha, null));

        ReviewRunEntity run = reviews.selectOne(Wrappers.<ReviewRunEntity>lambdaQuery()
                .eq(ReviewRunEntity::getThreadId, agent.request.threadId())
                .eq(ReviewRunEntity::getHeadSha, headSha));
        assertThat(run.getStatus()).isEqualTo("FAILED");
        OutboxEventEntity event = outbox.selectOne(Wrappers.<OutboxEventEntity>lambdaQuery()
                .eq(OutboxEventEntity::getRunId, run.getId()));
        assertThat(event.getStatus()).isEqualTo("SENT");
    }

    private static String sign(String payload) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec("chain-test-secret".getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return "sha256=" + HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
    }

    @TestConfiguration
    static class TestAgentConfig {
        @Bean
        ProbeAgent probeAgent(ReviewRunMapper reviews) {
            return new ProbeAgent(reviews);
        }
    }

    static final class ProbeAgent implements ReviewerAgent {
        private final ReviewRunMapper reviews;
        final CountDownLatch called = new CountDownLatch(1);
        volatile ReviewRequest request;

        ProbeAgent(ReviewRunMapper reviews) {
            this.reviews = reviews;
        }

        @Override
        public void call(ReviewRequest request) {
            this.request = request;
            ReviewRunEntity update = new ReviewRunEntity();
            update.setStatus("FAILED");
            int changed = reviews.update(update, Wrappers.<ReviewRunEntity>lambdaUpdate()
                    .eq(ReviewRunEntity::getThreadId, request.threadId())
                    .eq(ReviewRunEntity::getHeadSha, request.headSha())
                    .eq(ReviewRunEntity::getStatus, "RUNNING"));
            if (changed != 1) {
                throw new IllegalStateException("test Agent did not receive a RUNNING task");
            }
            called.countDown();
        }
    }
}

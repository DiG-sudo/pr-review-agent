package com.guodi.pragent.reviewer.github;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

class GitHubWebhookEventTest {

    private static final String SECRET = "test-secret";
    private static final String SHA = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private final GitHubWebhookEvent webhook = new GitHubWebhookEvent(new ObjectMapper(), SECRET);

    @Test
    void openedAndUpdatedRevisionsTriggerTheSameThread() throws Exception {
        for (String action : new String[] {"opened", "reopened", "synchronize"}) {
            byte[] body = payload(action);
            ReviewRequest request = webhook.parse(body, "pull_request", sign(body)).orElseThrow();
            assertThat(request.threadId()).isEqualTo("github:owner/repo#17");
            assertThat(request.repository()).isEqualTo("owner/repo");
            assertThat(request.pullRequestNumber()).isEqualTo(17);
            assertThat(request.headSha()).isEqualTo(SHA);
            assertThat(request.baseSha()).isEqualTo("b".repeat(40));
        }
    }

    @Test
    void ignoresOtherEventsAndActionsAfterCheckingTheirSignature() throws Exception {
        byte[] body = payload("closed");
        assertThat(webhook.parse(body, "pull_request", sign(body))).isEmpty();
        assertThat(webhook.parse(body, "issues", sign(body))).isEmpty();
    }

    @Test
    void rejectsTamperedPayloadAndMissingSignature() throws Exception {
        byte[] body = payload("opened");
        byte[] changed = payload("reopened");
        assertThatThrownBy(() -> webhook.parse(changed, "pull_request", sign(body)))
                .isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> webhook.parse(body, "pull_request", null))
                .isInstanceOf(SecurityException.class);
    }

    private static byte[] payload(String action) {
        return ("{\"action\":\"" + action + "\",\"number\":17,"
                + "\"repository\":{\"full_name\":\"owner/repo\"},"
                + "\"pull_request\":{\"head\":{\"sha\":\"" + SHA + "\"},"
                + "\"base\":{\"sha\":\"" + "b".repeat(40) + "\"}}}")
                .getBytes(StandardCharsets.UTF_8);
    }

    private static String sign(byte[] body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return "sha256=" + HexFormat.of().formatHex(mac.doFinal(body));
    }
}

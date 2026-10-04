package com.guodi.pragent.reviewer.github;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import com.fasterxml.jackson.databind.ObjectMapper;

import static org.mockito.Mockito.mock;

class GitHubWebhookControllerTest {

    @Test
    void acceptsOnlyVerifiedPullRequestsAfterServicePersistsThem() throws Exception {
        GitHubWebhookService service = mock(GitHubWebhookService.class);
        when(service.accept(any())).thenReturn(GitHubWebhookService.AcceptResult.CREATED);
        GitHubWebhookController controller = new GitHubWebhookController(new ObjectMapper(),
                "secret", service);
        byte[] payload = payload();

        assertThat(controller.receive(payload, "pull_request", sign(payload)).getStatusCode())
                .isEqualTo(HttpStatus.ACCEPTED);
        verify(service).accept(new ReviewRequest("github:owner/repo#7", "owner/repo", 7,
                "a".repeat(40), "b".repeat(40), null));
        assertThat(controller.receive(payload, "pull_request", "sha256=" + "0".repeat(64))
                .getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(controller.receive("null".getBytes(StandardCharsets.UTF_8),
                "pull_request", sign("null".getBytes(StandardCharsets.UTF_8))).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void duplicateNotificationStillAcknowledgesWithoutAnotherTask() throws Exception {
        GitHubWebhookService service = mock(GitHubWebhookService.class);
        when(service.accept(any())).thenReturn(GitHubWebhookService.AcceptResult.DUPLICATE);
        GitHubWebhookController controller = new GitHubWebhookController(new ObjectMapper(),
                "secret", service);
        byte[] payload = payload();

        assertThat(controller.receive(payload, "pull_request", sign(payload)).getStatusCode())
                .isEqualTo(HttpStatus.ACCEPTED);
        assertThat(controller.receive(payload, "pull_request", sign(payload)).getBody())
                .isEqualTo("duplicate");
    }

    @Test
    void missingSecretRejectsBeforePersistence() {
        GitHubWebhookService service = mock(GitHubWebhookService.class);
        GitHubWebhookController controller = new GitHubWebhookController(new ObjectMapper(),
                "", service);
        assertThat(controller.receive(payload(), "pull_request", null).getStatusCode())
                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        verifyNoInteractions(service);
    }

    private static byte[] payload() {
        return ("{\"action\":\"opened\",\"number\":7,"
                + "\"repository\":{\"full_name\":\"owner/repo\"},"
                + "\"pull_request\":{\"head\":{\"sha\":\"" + "a".repeat(40) + "\"},"
                + "\"base\":{\"sha\":\"" + "b".repeat(40) + "\"}}}")
                .getBytes(StandardCharsets.UTF_8);
    }

    private static String sign(byte[] payload) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec("secret".getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return "sha256=" + HexFormat.of().formatHex(mac.doFinal(payload));
    }
}

package com.guodi.pragent.reviewer.github;

import java.io.IOException;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.TransactionException;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.databind.ObjectMapper;

/** Accepts signed GitHub PR notifications after the review task is durable in MySQL. */
@RestController
public final class GitHubWebhookController {

    private static final Logger log = LoggerFactory.getLogger(GitHubWebhookController.class);

    private final GitHubWebhookService webhookService;
    private final GitHubWebhookEvent webhookEvent;

    public GitHubWebhookController(ObjectMapper json, @Value("${GITHUB_WEBHOOK_SECRET:}") String secret,
            GitHubWebhookService webhookService) {
        this.webhookService = webhookService;
        this.webhookEvent = secret == null || secret.isBlank()
                ? null : new GitHubWebhookEvent(json, secret);
    }

    @PostMapping("/github/webhook")
    public ResponseEntity<String> receive(@RequestBody byte[] body,
            @RequestHeader(value = "X-GitHub-Event", required = false) String event,
            @RequestHeader(value = "X-Hub-Signature-256", required = false) String signature) {
        if (webhookEvent == null) {
            log.error("GitHub webhook rejected: GITHUB_WEBHOOK_SECRET is not configured");
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body("GITHUB_WEBHOOK_SECRET is not configured");
        }

        Optional<ReviewRequest> request;
        try {
            request = webhookEvent.parse(body, event, signature);
        } catch (SecurityException error) {
            log.warn("GitHub webhook rejected: invalid signature");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("invalid webhook signature");
        } catch (IllegalArgumentException | IOException error) {
            log.warn("GitHub webhook rejected: invalid payload for event {}", event);
            return ResponseEntity.badRequest().body("invalid webhook payload");
        }
        if (request.isEmpty()) {
            log.info("GitHub webhook ignored: event={}", event);
            return ResponseEntity.ok("ignored");
        }

        ReviewRequest review = request.get();
        try {
            GitHubWebhookService.AcceptResult result = webhookService.accept(review);
            log.info("GitHub PR webhook accepted: repository={}, pr={}, headSha={}, result={}",
                    review.repository(), review.pullRequestNumber(), review.headSha(), result);
            return ResponseEntity.status(HttpStatus.ACCEPTED).body(result.name().toLowerCase());
        } catch (DataAccessException | TransactionException error) {
            log.error("GitHub PR webhook intake failed: repository={}, pr={}, headSha={}",
                    review.repository(), review.pullRequestNumber(), review.headSha(), error);
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body("webhook intake unavailable");
        }
    }
}

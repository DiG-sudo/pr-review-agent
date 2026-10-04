package com.guodi.pragent.reviewer.github;

import java.util.UUID;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.guodi.pragent.tables.outbox.OutboxEventEntity;
import com.guodi.pragent.tables.outbox.OutboxEventMapper;
import com.guodi.pragent.tables.reviewrun.ReviewRunEntity;
import com.guodi.pragent.tables.reviewrun.ReviewRunMapper;

/** Persists one review task and its Outbox notification before HTTP acknowledgement. */
@Service
public class GitHubWebhookService {

    public enum AcceptResult { CREATED, DUPLICATE }

    private final ReviewRunMapper reviewRunMapper;
    private final OutboxEventMapper outboxEventMapper;

    public GitHubWebhookService(ReviewRunMapper reviewRunMapper, OutboxEventMapper outboxEventMapper) {
        this.reviewRunMapper = reviewRunMapper;
        this.outboxEventMapper = outboxEventMapper;
    }

    @Transactional
    public AcceptResult accept(ReviewRequest request) {
        ReviewRunEntity review = new ReviewRunEntity();
        review.setThreadId(request.threadId());
        review.setHeadSha(request.headSha());
        review.setBaseSha(request.baseSha());
        review.setRepository(request.repository());
        review.setPullRequestNumber(request.pullRequestNumber());
        review.setStatus("PENDING");
        review.setPublicationKey(UUID.randomUUID().toString());

        try {
            reviewRunMapper.insert(review);
        } catch (DuplicateKeyException duplicate) {
            // The unique (thread_id, head_sha) key means this revision was already accepted.
            return AcceptResult.DUPLICATE;
        }
        enqueue(review.getId());
        return AcceptResult.CREATED;
    }

    private void enqueue(Long taskId) {
        OutboxEventEntity event = new OutboxEventEntity();
        event.setRunId(taskId);
        event.setEventType("RUN_ACCEPTED");
        event.setStatus("PENDING");
        outboxEventMapper.insert(event);

        // ReviewOutboxPublisher sends this row to Redis Stream after accept() commits.
        // ReviewStreamConsumer will call ReviewerAgent when it becomes available.
    }
}

package com.guodi.pragent.entry.webhook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;

import com.guodi.pragent.persistence.outbox.OutboxEventEntity;
import com.guodi.pragent.persistence.outbox.OutboxEventMapper;
import com.guodi.pragent.persistence.reviewrun.ReviewRunEntity;
import com.guodi.pragent.persistence.reviewrun.ReviewRunMapper;
import com.guodi.pragent.reviewer.ReviewRequest;

class GitHubWebhookServiceTest {

    private static final ReviewRequest REQUEST = new ReviewRequest(
            "github:owner/repo#7", "owner/repo", 7, "a".repeat(40), "b".repeat(40), null);

    @Test
    void newRevisionWritesTaskAndOutboxEvent() {
        ReviewRunMapper runs = mock(ReviewRunMapper.class);
        OutboxEventMapper outbox = mock(OutboxEventMapper.class);
        doAnswer(invocation -> {
            ReviewRunEntity row = invocation.getArgument(0);
            assertThat(row.getBaseSha()).isEqualTo(REQUEST.baseSha());
            assertThat(row.getPublicationKey()).isNotBlank();
            row.setId(42L);
            return 1;
        }).when(runs).insert(any(ReviewRunEntity.class));

        assertThat(new GitHubWebhookService(runs, outbox).accept(REQUEST))
                .isEqualTo(GitHubWebhookService.AcceptResult.CREATED);
        verify(outbox).insert(argThat((OutboxEventEntity event) ->
                Long.valueOf(42).equals(event.getRunId())
                        && "RUN_ACCEPTED".equals(event.getEventType())
                        && "PENDING".equals(event.getStatus())));
    }

    @Test
    void duplicateRevisionDoesNotWriteAnotherOutboxEvent() {
        ReviewRunMapper runs = mock(ReviewRunMapper.class);
        OutboxEventMapper outbox = mock(OutboxEventMapper.class);
        doThrow(new DuplicateKeyException("uq_review_run_thread_revision"))
                .when(runs).insert(any(ReviewRunEntity.class));

        assertThat(new GitHubWebhookService(runs, outbox).accept(REQUEST))
                .isEqualTo(GitHubWebhookService.AcceptResult.DUPLICATE);
        verifyNoInteractions(outbox);
    }
}

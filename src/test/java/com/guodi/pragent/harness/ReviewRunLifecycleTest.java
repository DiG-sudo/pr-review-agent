package com.guodi.pragent.harness;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.guodi.pragent.persistence.reviewrun.ReviewRunEntity;
import com.guodi.pragent.persistence.reviewrun.ReviewRunMapper;
import com.guodi.pragent.preparation.GitHubWorkspacePreparer;
import com.guodi.pragent.preparation.GitHubWorkspacePreparer.ReviewWorkspace;
import com.guodi.pragent.reviewer.GitHubReviewLookup;
import com.guodi.pragent.reviewer.GitHubReviewPublisher;
import com.guodi.pragent.runtime.ReviewRunResult;
import com.guodi.pragent.runtime.ReviewStatus;
import com.guodi.pragent.runtime.tool.ToolRegistry;

class ReviewRunLifecycleTest {
    private final ReviewRunMapper runs = mock(ReviewRunMapper.class);
    private final GitHubWorkspacePreparer preparer = mock(GitHubWorkspacePreparer.class);
    private final GitHubReviewLookup lookup = mock(GitHubReviewLookup.class);
    private final GitHubReviewPublisher publisher = mock(GitHubReviewPublisher.class);
    private final ReviewRunRestorer restorer = mock(ReviewRunRestorer.class);
    private final ObjectMapper json = new ObjectMapper();
    private final ReviewHarness harness = new ReviewHarness(runs, mock(ToolRoundCoordinator.class),
            mock(ReviewContextBuilder.class), new ToolRegistry(List.of()), json,
            restorer, preparer, lookup, publisher);

    private ReviewRunEntity task(ReviewStatus status) {
        ReviewRunEntity task = new ReviewRunEntity();
        task.setId(7L);
        task.setThreadId("owner/repo#1");
        task.setRepository("owner/repo");
        task.setPullRequestNumber(1);
        task.setHeadSha("a".repeat(40));
        task.setBaseSha("b".repeat(40));
        task.setPublicationKey("run-7");
        task.setStatus(status.name());
        when(runs.selectById(7L)).thenReturn(task);
        return task;
    }

    private ReviewWorkspace workspace(Path directory) {
        ReviewWorkspace workspace = mock(ReviewWorkspace.class);
        when(workspace.workspaceDirectory()).thenReturn(directory);
        when(workspace.fileDiffs()).thenReturn(List.of());
        when(preparer.prepareWorkspace(any())).thenReturn(workspace);
        when(runs.updateById(any(ReviewRunEntity.class))).thenReturn(1);
        return workspace;
    }

    @Test
    void existingRemoteReviewCompletesWithoutWorkspaceOrAnotherPost() throws Exception {
        ReviewRunEntity task = task(ReviewStatus.PUBLICATION_READY);
        when(lookup.findPublished("owner/repo", 1, task.getHeadSha(), "run-7"))
                .thenReturn(OptionalLong.of(42));
        when(runs.update(any(ReviewRunEntity.class), any())).thenReturn(1);

        var result = harness.aroundRun(7L, execution -> { throw new AssertionError(); });

        assertThat(result.status()).isEqualTo(ReviewStatus.PUBLISHED);
        ArgumentCaptor<ReviewRunEntity> saved = ArgumentCaptor.forClass(ReviewRunEntity.class);
        verify(runs).update(saved.capture(), any());
        assertThat(saved.getValue().getExternalReviewId()).isEqualTo("42");
        assertThat(saved.getValue().getStatus()).isEqualTo("PUBLISHED");
        verifyNoInteractions(preparer, publisher);
    }

    @Test
    void freshRunClosesWorkspaceAndPublishesSavedPayload(@TempDir Path directory) throws Exception {
        ReviewRunEntity task = task(ReviewStatus.RUNNING);
        ReviewWorkspace workspace = workspace(directory);
        when(lookup.findPublished(any(), anyInt(), any(), any())).thenReturn(OptionalLong.empty());
        task.setPublicationPayloadJson(json.writeValueAsString(Map.of("body", "saved body")));
        when(publisher.publish("owner/repo", 1, task.getHeadSha(), "run-7", "saved body")).thenReturn(42L);
        when(runs.update(any(ReviewRunEntity.class), any())).thenReturn(1);

        var result = harness.aroundRun(7L, execution -> {
            assertThat(execution.getHistory()).isEmpty();
            task.setStatus("PUBLICATION_READY");
            return new ReviewRunResult(ReviewStatus.PUBLICATION_READY, null);
        });

        assertThat(result.status()).isEqualTo(ReviewStatus.PUBLISHED);
        verify(workspace).close();
        var order = inOrder(publisher, runs);
        order.verify(publisher).publish("owner/repo", 1, task.getHeadSha(), "run-7", "saved body");
        order.verify(runs).update(any(ReviewRunEntity.class), any());
    }

    @Test
    void budgetFailureIsPersistedAndWorkspaceClosed(@TempDir Path directory) throws Exception {
        task(ReviewStatus.RUNNING);
        ReviewWorkspace workspace = workspace(directory);
        when(runs.update(any(ReviewRunEntity.class), any())).thenReturn(1);
        var result = harness.aroundRun(7L, execution -> new ReviewRunResult(ReviewStatus.FAILED, null));
        assertThat(result.status()).isEqualTo(ReviewStatus.FAILED);
        ArgumentCaptor<ReviewRunEntity> saved = ArgumentCaptor.forClass(ReviewRunEntity.class);
        verify(runs).update(saved.capture(), any());
        assertThat(saved.getValue().getStatus()).isEqualTo("FAILED");
        assertThat(saved.getValue().getFinalResultJson()).contains("budget exhausted");
        verify(workspace).close();
        verifyNoInteractions(lookup, publisher);
    }

    @Test
    void executionExceptionPropagatesAndWorkspaceCloses(@TempDir Path directory) throws Exception {
        task(ReviewStatus.RUNNING);
        ReviewWorkspace workspace = workspace(directory);
        assertThatThrownBy(() -> harness.aroundRun(7L, execution -> {
            throw new IllegalStateException("model unavailable");
        })).hasMessage("model unavailable");
        verify(workspace).close();
        verify(runs, never()).update(any(ReviewRunEntity.class), any());
    }

    @Test
    void lookupFailureDoesNotMarkPublished() throws Exception {
        task(ReviewStatus.PUBLICATION_READY);
        when(lookup.findPublished(any(), anyInt(), any(), any())).thenThrow(new IOException("timeout"));
        assertThatThrownBy(() -> harness.aroundRun(7L, execution -> { throw new AssertionError(); }))
                .isInstanceOf(java.io.UncheckedIOException.class);
        verify(runs, never()).update(any(ReviewRunEntity.class), any());
        verifyNoInteractions(publisher, preparer);
    }

    @Test
    void remoteSuccessWithoutLocalCommitDoesNotReturnPublished() throws Exception {
        task(ReviewStatus.PUBLICATION_READY);
        when(lookup.findPublished(any(), anyInt(), any(), any())).thenReturn(OptionalLong.of(42));
        when(runs.update(any(ReviewRunEntity.class), any())).thenReturn(0);
        assertThatThrownBy(() -> harness.aroundRun(7L, execution -> { throw new AssertionError(); }))
                .hasMessage("发布结果未保存: 7");
        verifyNoInteractions(publisher);
    }
}

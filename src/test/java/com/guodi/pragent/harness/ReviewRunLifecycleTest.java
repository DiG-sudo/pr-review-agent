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
import com.guodi.pragent.persistence.reviewagent.ReviewAgentEntity;
import com.guodi.pragent.persistence.reviewagent.ReviewAgentStore;
import com.guodi.pragent.persistence.reviewrun.ReviewRunEntity;
import com.guodi.pragent.persistence.reviewrun.ReviewRunMapper;
import com.guodi.pragent.preparation.GitHubWorkspacePreparer;
import com.guodi.pragent.preparation.GitHubWorkspacePreparer.ReviewWorkspace;
import com.guodi.pragent.preparation.ReviewPlanGenerator;
import com.guodi.pragent.reviewer.GitHubReviewLookup;
import com.guodi.pragent.reviewer.GitHubReviewPublisher;
import com.guodi.pragent.runtime.AgentRunResult;
import com.guodi.pragent.runtime.ReviewRunResult;
import com.guodi.pragent.runtime.ReviewStatus;
import com.guodi.pragent.runtime.tool.ToolRegistry;

class ReviewRunLifecycleTest {
    private final ReviewRunMapper runs = mock(ReviewRunMapper.class);
    private final GitHubWorkspacePreparer preparer = mock(GitHubWorkspacePreparer.class);
    private final GitHubReviewLookup lookup = mock(GitHubReviewLookup.class);
    private final GitHubReviewPublisher publisher = mock(GitHubReviewPublisher.class);
    private final ReviewAgentStore agentStore = mock(ReviewAgentStore.class);
    private final ReviewPlanGenerator planGenerator = mock(ReviewPlanGenerator.class);
    private final ReviewRunRestorer restorer = mock(ReviewRunRestorer.class);
    private final ObjectMapper json = new ObjectMapper();
    private final ReviewHarness harness = new ReviewHarness(runs, mock(ToolRoundCoordinator.class),
            mock(ReviewContextBuilder.class), new ToolRegistry(List.of()), json,
            agentStore, planGenerator,
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

    private ReviewAgentEntity unfinishedAgent() throws Exception {
        ReviewAgentEntity agent = new ReviewAgentEntity();
        agent.setId(9L);
        agent.setRunId(7L);
        agent.setAgentIndex(0);
        agent.setReviewStateJson("{\"findings\":[]}");
        when(agentStore.findByRunId(7L)).thenReturn(List.of(agent));
        when(restorer.loadExecution(any(), eq(agent), any())).thenReturn(mock(com.guodi.pragent.runtime.ReviewExecution.class));
        return agent;
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
        task.setPublicationPayloadJson(json.writeValueAsString(Map.of("body", "No issues found.")));
        when(publisher.publish("owner/repo", 1, task.getHeadSha(), "run-7", "No issues found.")).thenReturn(42L);
        when(runs.update(any(ReviewRunEntity.class), any())).thenReturn(1);
        ReviewAgentEntity agent = unfinishedAgent();
        when(agentStore.markPublicationReady(eq(7L), anyString(), anyString())).thenAnswer(call -> {
            task.setStatus("PUBLICATION_READY");
            task.setPublicationPayloadJson(call.getArgument(2));
            return task;
        });

        var result = harness.aroundRun(7L, execution -> {
            agent.setSuccess(true);
            return AgentRunResult.succeeded();
        });

        assertThat(result.status()).isEqualTo(ReviewStatus.PUBLISHED);
        verify(workspace).close();
        var order = inOrder(publisher, runs);
        order.verify(publisher).publish("owner/repo", 1, task.getHeadSha(), "run-7", "No issues found.");
        order.verify(runs).update(any(ReviewRunEntity.class), any());
    }

    @Test
    void budgetFailureIsPersistedAndWorkspaceClosed(@TempDir Path directory) throws Exception {
        task(ReviewStatus.RUNNING);
        ReviewWorkspace workspace = workspace(directory);
        ReviewAgentEntity agent = unfinishedAgent();
        var result = harness.aroundRun(7L,
                execution -> AgentRunResult.failed("Model call budget exhausted"));
        assertThat(result.status()).isEqualTo(ReviewStatus.FAILED);
        verify(agentStore).failAgentAndRun(7L, agent.getId(), "Model call budget exhausted");
        verify(workspace).close();
        verifyNoInteractions(lookup, publisher);
    }

    @Test
    void executionExceptionPropagatesAndWorkspaceCloses(@TempDir Path directory) throws Exception {
        task(ReviewStatus.RUNNING);
        ReviewWorkspace workspace = workspace(directory);
        unfinishedAgent();
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

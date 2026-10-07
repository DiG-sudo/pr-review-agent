package com.guodi.pragent.harness;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.guodi.pragent.persistence.reviewrun.ReviewRunEntity;
import com.guodi.pragent.persistence.reviewrun.ReviewRunMapper;
import com.guodi.pragent.preparation.GitHubWorkspacePreparer.ReviewWorkspace;
import com.guodi.pragent.reviewer.tool.ReviewToolContext;
import com.guodi.pragent.runtime.ReviewExecution;
import com.guodi.pragent.runtime.tool.ToolRegistry;

class ReviewRunInitializationTest {
    @Test
    void persistsInitialMessagesAndEmptyStateBeforeReturningExecution(@TempDir Path directory) throws Exception {
        ReviewRunMapper runs = mock(ReviewRunMapper.class);
        ObjectMapper json = new ObjectMapper();
        ReviewHarness harness = new ReviewHarness(runs, mock(ToolRoundCoordinator.class),
                mock(ReviewContextBuilder.class), new ToolRegistry(List.of()), json, mock(ReviewRunRestorer.class),
                mock(com.guodi.pragent.preparation.GitHubWorkspacePreparer.class),
                mock(com.guodi.pragent.reviewer.GitHubReviewLookup.class),
                mock(com.guodi.pragent.reviewer.GitHubReviewPublisher.class));
        ReviewRunEntity task = new ReviewRunEntity();
        task.setId(7L);
        task.setThreadId("owner/repo#1");
        task.setRepository("owner/repo");
        task.setPullRequestNumber(1);
        task.setHeadSha("a".repeat(40));
        task.setBaseSha("b".repeat(40));
        task.setStatus("RUNNING");
        ReviewWorkspace workspace = mock(ReviewWorkspace.class);
        when(workspace.workspaceDirectory()).thenReturn(directory);
        when(workspace.fileDiffs()).thenReturn(List.of());
        when(runs.updateById(any(ReviewRunEntity.class))).thenReturn(1);

        ReviewExecution execution = ReflectionTestUtils.invokeMethod(harness, "initializeRun", task, workspace);

        ArgumentCaptor<ReviewRunEntity> saved = ArgumentCaptor.forClass(ReviewRunEntity.class);
        verify(runs).updateById(saved.capture());
        var messages = json.readTree(saved.getValue().getInitialMessagesJson());
        assertThat(messages.size()).isEqualTo(2);
        assertThat(messages.get(0).get("type").asText()).isEqualTo("system");
        assertThat(messages.get(1).get("text").asText()).contains("owner/repo");
        assertThat(json.readTree(saved.getValue().getReviewStateJson()).get("findings").isEmpty()).isTrue();
        assertThat(execution.getHistory()).isEmpty();
        assertThat(execution.getModelCalls()).isZero();
        assertThat(saved.getValue().getModelCalls()).isZero();
        assertThat(saved.getValue().getMaxModelCalls()).isEqualTo(execution.getMaxModelCalls());
        assertThat(execution.getNextToolRoundNumber()).isEqualTo(1);
        assertThat(ReviewToolContext.from(execution.getToolContext()).fixtureRoot()).isEqualTo(directory.toRealPath());

    }
}

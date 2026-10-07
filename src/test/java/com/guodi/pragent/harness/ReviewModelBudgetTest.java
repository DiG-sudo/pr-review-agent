package com.guodi.pragent.harness;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ToolContext;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.guodi.pragent.persistence.reviewrun.ReviewRunEntity;
import com.guodi.pragent.persistence.reviewrun.ReviewRunMapper;
import com.guodi.pragent.preparation.GitHubWorkspacePreparer;
import com.guodi.pragent.reviewer.GitHubReviewLookup;
import com.guodi.pragent.reviewer.GitHubReviewPublisher;
import com.guodi.pragent.reviewer.ReviewState;
import com.guodi.pragent.reviewer.tool.ReviewToolContext;
import com.guodi.pragent.runtime.ReviewExecution;
import com.guodi.pragent.runtime.ReviewStatus;
import com.guodi.pragent.runtime.tool.ToolRegistry;

class ReviewModelBudgetTest {
    private final ReviewRunMapper runs = mock(ReviewRunMapper.class);
    private final ReviewHarness harness = new ReviewHarness(runs, mock(ToolRoundCoordinator.class),
            new ReviewContextBuilder(new ObjectMapper(), 10, 12000), new ToolRegistry(List.of()),
            new ObjectMapper(), mock(ReviewRunRestorer.class), mock(GitHubWorkspacePreparer.class),
            mock(GitHubReviewLookup.class), mock(GitHubReviewPublisher.class));

    private ReviewExecution execution(Path workspace) {
        ReviewRunEntity task = new ReviewRunEntity();
        task.setStatus("RUNNING");
        when(runs.selectById(7L)).thenReturn(task);
        ToolContext context = new ToolContext(Map.of(ReviewToolContext.KEY,
                new ReviewToolContext(workspace, new ReviewState("thread"))));
        return new ReviewExecution(7L, List.of(new UserMessage("review")), List.of(), context, 4, 5, 1);
    }

    @Test
    void failedRequestConsumesPersistedBudgetAndStopsAtLimit(@TempDir Path workspace) {
        ReviewExecution execution = execution(workspace);
        when(runs.update(any(ReviewRunEntity.class), any())).thenReturn(1);

        assertThatThrownBy(() -> harness.aroundReasoning(execution, prompt -> {
            ArgumentCaptor<ReviewRunEntity> saved = ArgumentCaptor.forClass(ReviewRunEntity.class);
            verify(runs).update(saved.capture(), any());
            assertThat(saved.getValue().getModelCalls()).isEqualTo(5);
            throw new IllegalStateException("model timeout");
        })).hasMessage("model timeout");

        assertThat(execution.getModelCalls()).isEqualTo(5);
        var stopped = harness.aroundReasoning(execution, prompt -> { throw new AssertionError("budget exhausted"); });
        assertThat(stopped.status()).isEqualTo(ReviewStatus.FAILED);
        verify(runs, times(1)).update(any(ReviewRunEntity.class), any());
    }

    @Test
    void failedCounterWritePreventsModelCall(@TempDir Path workspace) {
        ReviewExecution execution = execution(workspace);
        when(runs.update(any(ReviewRunEntity.class), any())).thenReturn(0);
        assertThatThrownBy(() -> harness.aroundReasoning(execution,
                prompt -> { throw new AssertionError("counter must be committed first"); }))
                .hasMessageContaining("模型调用次数未保存");
        assertThat(execution.getModelCalls()).isEqualTo(4);
    }
}

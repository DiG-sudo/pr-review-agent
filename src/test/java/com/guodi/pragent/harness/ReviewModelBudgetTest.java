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
import com.guodi.pragent.persistence.reviewagent.ReviewAgentStore;
import com.guodi.pragent.preparation.GitHubWorkspacePreparer;
import com.guodi.pragent.preparation.ReviewPlanGenerator;
import com.guodi.pragent.reviewer.GitHubReviewLookup;
import com.guodi.pragent.reviewer.GitHubReviewPublisher;
import com.guodi.pragent.reviewer.ReviewState;
import com.guodi.pragent.reviewer.tool.ReviewToolContext;
import com.guodi.pragent.runtime.AgentRunFailedException;
import com.guodi.pragent.runtime.ReviewExecution;
import com.guodi.pragent.runtime.tool.ToolRegistry;

class ReviewModelBudgetTest {
    private final ReviewRunMapper runs = mock(ReviewRunMapper.class);
    private final ReviewAgentStore agentStore = mock(ReviewAgentStore.class);
    private final ReviewHarness harness = new ReviewHarness(runs, mock(ToolRoundCoordinator.class),
            new ReviewContextBuilder(new ObjectMapper(), 10, 12000), new ToolRegistry(List.of()),
            new ObjectMapper(), agentStore, mock(ReviewPlanGenerator.class),
            mock(ReviewRunRestorer.class), mock(GitHubWorkspacePreparer.class),
            mock(GitHubReviewLookup.class), mock(GitHubReviewPublisher.class));

    private ReviewExecution execution(Path workspace) {
        ReviewRunEntity task = new ReviewRunEntity();
        task.setStatus("RUNNING");
        when(runs.selectById(7L)).thenReturn(task);
        ToolContext context = new ToolContext(Map.of(ReviewToolContext.KEY,
                new ReviewToolContext(workspace, new ReviewState("thread"), "")));
        return new ReviewExecution(7L, 11L, List.of(new UserMessage("review")),
                List.of(), context, 4, 5, 1);
    }

    @Test
    void failedRequestConsumesPersistedBudgetAndStopsAtLimit(@TempDir Path workspace) {
        ReviewExecution execution = execution(workspace);
        when(agentStore.reserveModelCall(7L, 11L, 4, 5)).thenReturn(5);
        when(agentStore.reserveModelCall(7L, 11L, 5, 5)).thenThrow(new AgentRunFailedException("Model call budget exhausted"));

        assertThatThrownBy(() -> harness.aroundReasoning(execution, prompt -> {
            verify(agentStore).reserveModelCall(7L, 11L, 4, 5);
            throw new IllegalStateException("model timeout");
        })).hasMessage("model timeout");

        assertThat(execution.getModelCalls()).isEqualTo(5);
        assertThatThrownBy(() -> harness.aroundReasoning(execution,
                prompt -> { throw new AssertionError("budget exhausted"); }))
                .isInstanceOf(AgentRunFailedException.class)
                .hasMessage("Model call budget exhausted");
        verify(agentStore).reserveModelCall(7L, 11L, 5, 5);
    }

    @Test
    void failedCounterWritePreventsModelCall(@TempDir Path workspace) {
        ReviewExecution execution = execution(workspace);
        when(agentStore.reserveModelCall(7L, 11L, 4, 5)).thenThrow(new IllegalStateException("Agent 模型调用次数未保存: 11"));
        assertThatThrownBy(() -> harness.aroundReasoning(execution,
                prompt -> { throw new AssertionError("counter must be committed first"); }))
                .hasMessageContaining("模型调用次数未保存");
        assertThat(execution.getModelCalls()).isEqualTo(4);
    }
}

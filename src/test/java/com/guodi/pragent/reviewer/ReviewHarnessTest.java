package com.guodi.pragent.reviewer;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ToolContext;

import com.guodi.pragent.harness.ReviewHarness;
import com.guodi.pragent.harness.ToolRoundCoordinator;
import com.guodi.pragent.persistence.reviewrun.ReviewRunEntity;
import com.guodi.pragent.persistence.reviewrun.ReviewRunMapper;
import com.guodi.pragent.persistence.reviewagent.ReviewAgentStore;
import com.guodi.pragent.preparation.ReviewPlanGenerator;
import com.guodi.pragent.reviewer.tool.ReviewToolContext;
import com.guodi.pragent.runtime.ReviewExecution;
import com.guodi.pragent.runtime.ReviewStatus;

class ReviewHarnessTest {

    @Test
    void nonRunningStatesRejectReasoningBeforeAnyModelCall(@TempDir Path workspace) {
        ReviewRunMapper reviews = mock(ReviewRunMapper.class);
        ReviewHarness harness = new ReviewHarness(reviews, mock(ToolRoundCoordinator.class),
                mock(com.guodi.pragent.harness.ReviewContextBuilder.class),
                new com.guodi.pragent.runtime.tool.ToolRegistry(List.of()), new com.fasterxml.jackson.databind.ObjectMapper(),
                mock(ReviewAgentStore.class), mock(ReviewPlanGenerator.class),
                mock(com.guodi.pragent.harness.ReviewRunRestorer.class),
                mock(com.guodi.pragent.preparation.GitHubWorkspacePreparer.class),
                mock(GitHubReviewLookup.class), mock(GitHubReviewPublisher.class));
        ToolContext tools = new ToolContext(Map.of(ReviewToolContext.KEY, new ReviewToolContext(workspace, new ReviewState("thread"), "")));
        ReviewExecution execution = new ReviewExecution(7L, 11L,
                List.of(new UserMessage("review")), List.of(), tools, 8, 8, 1);
        for (ReviewStatus status : List.of(ReviewStatus.PUBLICATION_READY, ReviewStatus.PUBLISHED,
                ReviewStatus.FAILED, ReviewStatus.PENDING)) {
            ReviewRunEntity task = new ReviewRunEntity();
            task.setStatus(status.name());
            when(reviews.selectById(7L)).thenReturn(task);
            assertThatThrownBy(() -> harness.aroundReasoning(execution,
                    prompt -> { throw new AssertionError("stopped task must not call model"); }))
                    .hasMessageContaining("不允许推理");
        }
    }
}

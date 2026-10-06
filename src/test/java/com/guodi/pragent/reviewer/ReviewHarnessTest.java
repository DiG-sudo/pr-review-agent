package com.guodi.pragent.reviewer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ToolContext;

import com.guodi.pragent.harness.ReviewHarness;
import com.guodi.pragent.harness.ToolRoundCoordinator;
import com.guodi.pragent.persistence.reviewrun.ReviewRunEntity;
import com.guodi.pragent.persistence.reviewrun.ReviewRunMapper;
import com.guodi.pragent.reviewer.tool.ReviewToolContext;
import com.guodi.pragent.runtime.ReviewExecution;
import com.guodi.pragent.runtime.ReviewStatus;

class ReviewHarnessTest {

    @Test
    void readyAndTerminalStatesStopReasoningBeforeAnyModelCall(@TempDir Path workspace) {
        ReviewRunMapper reviews = mock(ReviewRunMapper.class);
        ReviewHarness harness = new ReviewHarness(reviews, mock(ToolRoundCoordinator.class));
        ToolContext tools = new ToolContext(Map.of(ReviewToolContext.KEY, new ReviewToolContext(workspace, new ReviewState("thread"))));
        ReviewExecution execution = new ReviewExecution(7L, List.of(new UserMessage("review")), List.of(), tools, 8, 8, 1);
        AtomicInteger calls = new AtomicInteger();
        for (ReviewStatus status : List.of(ReviewStatus.PUBLICATION_READY, ReviewStatus.PUBLISHED, ReviewStatus.FAILED)) {
            ReviewRunEntity task = new ReviewRunEntity();
            task.setStatus(status.name());
            when(reviews.selectById(7L)).thenReturn(task);
            var result = harness.aroundReasoning(execution, prompt -> {
                calls.incrementAndGet();
                throw new AssertionError("stopped task must not call model");
            });
            assertThat(result.status()).isEqualTo(status);
            assertThat(result.response()).isNull();
        }
        assertThat(calls.get()).isZero();
        ReviewRunEntity pending = new ReviewRunEntity();
        pending.setStatus(ReviewStatus.PENDING.name());
        when(reviews.selectById(7L)).thenReturn(pending);
        assertThatThrownBy(() -> harness.aroundReasoning(execution, prompt -> null)).hasMessageContaining("不允许推理");
    }
}

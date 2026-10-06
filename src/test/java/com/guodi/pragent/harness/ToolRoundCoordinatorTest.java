package com.guodi.pragent.harness;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ToolContext;

import com.guodi.pragent.persistence.toolround.ToolRoundStore;
import com.guodi.pragent.reviewer.ReviewState;
import com.guodi.pragent.reviewer.tool.ReviewToolContext;
import com.guodi.pragent.runtime.ReviewExecution;
import com.guodi.pragent.runtime.tool.ToolOutcome;
import com.guodi.pragent.runtime.tool.ToolRoundExecutor;

class ToolRoundCoordinatorTest {

    @Test
    void mixedPublicationIsRejectedButOtherCallsExecuteAndAllResultsPersist(@TempDir Path workspace) throws Exception {
        ToolRoundStore store = mock(ToolRoundStore.class);
        ToolRoundExecutor executor = mock(ToolRoundExecutor.class);
        ToolRoundCoordinator coordinator = new ToolRoundCoordinator(store, executor);
        ReviewState state = new ReviewState("thread");
        ToolContext context = new ToolContext(Map.of(ReviewToolContext.KEY, new ReviewToolContext(workspace, state)));
        ReviewExecution execution = new ReviewExecution(7L, List.of(new UserMessage("review")), List.of(), context, 1, 10, 3);
        AssistantMessage.ToolCall write = new AssistantMessage.ToolCall("write", "function", "add_finding", "{}");
        AssistantMessage.ToolCall publish = new AssistantMessage.ToolCall("publish", "function", "publish_review", "{}");
        AssistantMessage assistant = AssistantMessage.builder().content("").toolCalls(List.of(write, publish)).build();
        ToolOutcome written = new ToolOutcome(write, new ToolResponseMessage.ToolResponse(write.id(), write.name(), "added"), true);
        when(store.beginRound(7L, 3, assistant)).thenReturn(11L);
        when(executor.executeRound(any(), eq(context))).thenReturn(List.of(written));

        List<ToolOutcome> outcomes = coordinator.executeAndPersist(execution, assistant);

        ArgumentCaptor<AssistantMessage.ToolCall[]> calls = ArgumentCaptor.forClass(AssistantMessage.ToolCall[].class);
        verify(executor).executeRound(calls.capture(), eq(context));
        assertThat(calls.getValue()).containsExactly(write);
        assertThat(outcomes).hasSize(2);
        assertThat(outcomes.get(0)).isSameAs(written);
        assertThat(outcomes.get(1).isSuccess()).isFalse();
        assertThat(outcomes.get(1).getToolResponse().id()).isEqualTo(publish.id());
        verify(store).completeRound(7L, 11L, outcomes, state);
        assertThat(execution.getNextToolRoundNumber()).isEqualTo(4);
    }
}

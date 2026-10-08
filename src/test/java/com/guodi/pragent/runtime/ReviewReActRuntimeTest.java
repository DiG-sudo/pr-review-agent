package com.guodi.pragent.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.model.ToolContext;

import com.guodi.pragent.harness.ReviewHarness;
import com.guodi.pragent.runtime.tool.ToolOutcome;
import com.guodi.pragent.runtime.tool.ToolRoundResult;

class ReviewReActRuntimeTest {

    @Test
    void appendsTextAndToolResultsThenStopsWhenToolRoundCompletes() {
        ReviewHarness harness = mock(ReviewHarness.class);
        ChatModel model = mock(ChatModel.class);
        ReviewExecution execution = new ReviewExecution(7L, 11L, List.of(new UserMessage("review")),
                List.of(), new ToolContext(Map.of()), 0, 10, 1);
        AssistantMessage text = new AssistantMessage("Review complete.");
        AssistantMessage.ToolCall call = new AssistantMessage.ToolCall("publish", "function", "publish_review", "{}");
        AssistantMessage assistant = AssistantMessage.builder().content("").toolCalls(List.of(call)).build();
        ToolResponseMessage.ToolResponse response = new ToolResponseMessage.ToolResponse(call.id(), call.name(), "body");

        when(harness.aroundRun(eq(7L), any())).thenAnswer(invocation -> {
            Function<ReviewExecution, AgentRunResult> next = invocation.getArgument(1);
            assertThat(next.apply(execution).success()).isTrue();
            return new ReviewRunResult(ReviewStatus.PUBLICATION_READY);
        });
        when(harness.aroundReasoning(eq(execution), any())).thenReturn(
                new ChatResponse(List.of(new Generation(text))),
                new ChatResponse(List.of(new Generation(assistant))));
        when(harness.aroundToolRound(execution, assistant))
                .thenReturn(new ToolRoundResult(List.of(new ToolOutcome(call, response, true)), true));

        ReviewRunResult result = new ReviewReActRuntime(harness, model).run(7L);

        assertThat(result.status()).isEqualTo(ReviewStatus.PUBLICATION_READY);
        assertThat(execution.getHistory()).hasSize(4);
        assertThat(execution.getHistory().get(0)).isSameAs(text);
        assertThat(execution.getHistory().get(1)).isInstanceOf(UserMessage.class);
        assertThat(execution.getHistory().get(2)).isSameAs(assistant);
        assertThat(((ToolResponseMessage) execution.getHistory().get(3)).getResponses()).containsExactly(response);
        verifyNoInteractions(model);
    }
}

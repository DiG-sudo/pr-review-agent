package com.guodi.pragent.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;

import com.guodi.pragent.execution.ToolRoundCoordinator;
import com.guodi.pragent.harness.tool.ToolOutcome;
import com.guodi.pragent.harness.tool.ToolRegister;

class ReviewReActRuntimeTest {

    @Test
    void feedsTheCompletedToolRoundBackToTheModel() {
        ChatModel model = mock(ChatModel.class);
        ToolRoundCoordinator rounds = mock(ToolRoundCoordinator.class);
        ToolRegister tools = mock(ToolRegister.class);
        when(tools.callbacks()).thenReturn(new ToolCallback[0]);

        AssistantMessage.ToolCall call = new AssistantMessage.ToolCall(
                "call-1", "function", "get_diff", "{}");
        AssistantMessage toolRequest = AssistantMessage.builder()
                .content("").toolCalls(List.of(call)).build();
        AssistantMessage finalAnswer = new AssistantMessage("done");
        when(model.call(any(Prompt.class))).thenReturn(
                response(toolRequest), response(finalAnswer));
        when(rounds.execute(any(), anyInt(), any(), any())).thenReturn(
                List.of(new ToolOutcome(call,
                        new ToolResponseMessage.ToolResponse(
                                "call-1", "get_diff", "diff content"), true)));

        ReviewReActRuntime runtime = new ReviewReActRuntime(model, rounds, tools);
        ReviewRunResult result = runtime.run(new ReviewRunRequest(7L,
                List.of(new UserMessage("review this PR")),
                new ToolContext(Map.of()), 3));

        assertThat(result.status()).isEqualTo(ReviewRunResult.Status.MODEL_STOPPED);
        assertThat(result.steps()).isEqualTo(2);
        verify(rounds).execute(any(), anyInt(), any(), any());
        ArgumentCaptor<Prompt> prompts = ArgumentCaptor.forClass(Prompt.class);
        verify(model, org.mockito.Mockito.times(2)).call(prompts.capture());
        List<Message> secondMessages = prompts.getAllValues().get(1).getInstructions();
        assertThat(secondMessages).hasSize(3);
        assertThat(secondMessages.get(1)).isSameAs(toolRequest);
        assertThat(secondMessages.get(2)).isInstanceOf(ToolResponseMessage.class);
        assertThat(((ToolResponseMessage) secondMessages.get(2)).getResponses().getFirst()
                .responseData()).isEqualTo("diff content");
        assertThat(((ToolCallingChatOptions) prompts.getAllValues().get(0).getOptions())
                .getInternalToolExecutionEnabled()).isFalse();
    }

    private static ChatResponse response(AssistantMessage message) {
        return new ChatResponse(List.of(new Generation(message)));
    }
}

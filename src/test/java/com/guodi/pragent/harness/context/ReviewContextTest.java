package com.guodi.pragent.harness.context;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.guodi.pragent.reviewer.github.ReviewRequest;
import com.guodi.pragent.reviewer.state.Finding;

class ReviewContextTest {

    @Test
    void buildsTheSameContextShapeFromCommittedHistoryAndFindings() {
        ReviewContext context = new ReviewContext(new ObjectMapper());
        ReviewRequest request = new ReviewRequest(
                "thread-1", "owner/repo", 7, "abc123", "def456", null);
        AssistantMessage call = AssistantMessage.builder()
                .content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall(
                        "call-1", "function", "read_file", "{}")))
                .build();
        ToolResponseMessage result = ToolResponseMessage.builder()
                .responses(List.of(new ToolResponseMessage.ToolResponse(
                        "call-1", "read_file", "long source result")))
                .build();
        Finding finding = new Finding("finding-1", "high", "bug", "A.java", 7,
                "Wrong value", null, "open", null);

        List<Message> messages = context.buildModelMessages(new ReviewContext.Input(
                request, List.of(call, result), List.of(finding), 10, 5));

        assertThat(messages).hasSize(5);
        assertThat(messages.get(0).getText()).contains("pull request reviewer");
        assertThat(messages.get(1).getText()).contains("owner/repo", "abc123");
        assertThat(((AssistantMessage) messages.get(2)).getToolCalls().getFirst().id())
                .isEqualTo("call-1");
        assertThat(((ToolResponseMessage) messages.get(3)).getResponses().getFirst().responseData())
                .contains("tool result truncated");
        assertThat(messages.get(4).getText()).contains("finding-1", "Wrong value");
        assertThat(result.getResponses().getFirst().responseData())
                .isEqualTo("long source result");
    }
}

package com.guodi.pragent.harness;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.guodi.pragent.reviewer.Finding;
import com.guodi.pragent.reviewer.ReviewState;
import com.guodi.pragent.reviewer.tool.ReviewToolContext;
import com.guodi.pragent.runtime.ReviewExecution;

class ReviewContextBuilderTest {

    @Test
    void buildsTheSameContextShapeFromCommittedHistoryAndFindings(@TempDir Path workspace) {
        ReviewContextBuilder context = new ReviewContextBuilder(new ObjectMapper(), 10, 5);
        List<Message> initial = List.of(new SystemMessage("pull request reviewer"),
                new UserMessage("owner/repo at abc123"));
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

        ReviewState state = new ReviewState("thread");
        state.restoreFindings(List.of(finding));
        ToolContext tools = new ToolContext(Map.of(ReviewToolContext.KEY, new ReviewToolContext(workspace, state)));
        ReviewExecution execution = new ReviewExecution(7L, initial, List.of(call, result), tools, 1, 10, 2);
        List<Message> messages = context.buildModelMessages(execution);

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

package com.guodi.pragent.harness.tool;

import java.util.Objects;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;

import lombok.Getter;

/** Result of one tool call. */
@Getter
public final class ToolOutcome {

    private final AssistantMessage.ToolCall call;
    private final ToolResponseMessage.ToolResponse toolResponse;
    private final boolean success;

    public ToolOutcome(
            AssistantMessage.ToolCall call,
            ToolResponseMessage.ToolResponse toolResponse,
            boolean success) {
        this.call = Objects.requireNonNull(call, "call cannot be null");
        this.toolResponse = Objects.requireNonNull(toolResponse, "toolResponse cannot be null");
        this.success = success;
    }
}

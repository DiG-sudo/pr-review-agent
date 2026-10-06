package com.guodi.pragent.runtime.tool;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.stereotype.Component;

/** Executes one model-requested tool call. */
@Component
public final class ToolExecutor {

    private final ToolRegistry toolRegistry;

    public ToolExecutor(ToolRegistry toolRegistry) {
        this.toolRegistry = toolRegistry;
    }

    public ToolOutcome execute(AssistantMessage.ToolCall call, ToolContext toolContext) {
        ToolBinding binding = toolRegistry.findBinding(call.name());
        if (binding == null) {
            return failure(call, "Unknown tool: " + call.name());
        }
        return invokeCallback(call, binding, call.arguments(), toolContext);
    }

    private ToolOutcome invokeCallback(AssistantMessage.ToolCall call, ToolBinding binding, String arguments, ToolContext toolContext) {
        try {
            String result = binding.getToolCallback().call(arguments, toolContext);
            return outcome(call, result == null ? "" : result, true);
        } catch (Exception error) {
            String message = error.getMessage();
            if (message == null || message.isBlank()) {
                message = "Tool execution failed";
            } else {
                message = "Tool execution failed: " + message;
            }
            return failure(call, message);
        }
    }

    private ToolOutcome failure(AssistantMessage.ToolCall call, String message) {
        return outcome(call, message, false);
    }

    private ToolOutcome outcome(AssistantMessage.ToolCall call, String responseData, boolean success) {
        return new ToolOutcome(
                call,
                new ToolResponseMessage.ToolResponse(call.id(), call.name(), responseData),
                success);
    }
}

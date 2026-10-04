package com.guodi.pragent.core;

import java.util.List;
import java.util.Objects;

import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ToolContext;

/** Inputs prepared for one ReAct run by the caller. */
public record ReviewRunRequest(Long runId, List<Message> initialMessages,
        ToolContext toolContext, int maxSteps) {

    public ReviewRunRequest {
        Objects.requireNonNull(runId);
        initialMessages = List.copyOf(initialMessages);
        if (initialMessages.isEmpty()) {
            throw new IllegalArgumentException("initialMessages cannot be empty");
        }
        Objects.requireNonNull(toolContext);
        if (maxSteps <= 0) {
            throw new IllegalArgumentException("maxSteps must be positive");
        }
    }
}

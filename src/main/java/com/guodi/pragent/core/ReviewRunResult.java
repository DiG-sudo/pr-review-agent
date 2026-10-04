package com.guodi.pragent.core;

import java.util.List;

import org.springframework.ai.chat.messages.Message;

/** Loop outcome; the Harness will decide the persisted review status. */
public record ReviewRunResult(Status status, int steps, List<Message> messages) {

    public enum Status {
        TERMINAL_TOOL_SUCCEEDED,
        MODEL_STOPPED,
        MAX_STEPS_REACHED
    }

    public ReviewRunResult {
        messages = List.copyOf(messages);
    }
}

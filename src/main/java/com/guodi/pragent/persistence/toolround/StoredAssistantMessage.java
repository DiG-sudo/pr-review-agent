package com.guodi.pragent.persistence.toolround;

import java.util.List;

/** Stable JSON shape stored in {@code tool_round.assistant_message_json}. */
public record StoredAssistantMessage(String text, List<StoredToolCall> toolCalls) {

    public StoredAssistantMessage {
        toolCalls = List.copyOf(toolCalls);
    }

    public record StoredToolCall(String id, String type, String name, String arguments) {
    }
}

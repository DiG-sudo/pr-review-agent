package com.guodi.pragent.persistence.toolround;

import java.util.List;

/** Stable JSON shape for the complete response stored before context truncation. */
public record StoredToolResponseMessage(
        List<StoredToolResult> responses) {

    public StoredToolResponseMessage {
        responses = List.copyOf(responses);
    }

    public record StoredToolResult(
            String callId,
            String name,
            boolean success,
            String content) {
    }
}

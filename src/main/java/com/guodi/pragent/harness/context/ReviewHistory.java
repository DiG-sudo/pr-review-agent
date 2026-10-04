package com.guodi.pragent.harness.context;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;

/** Selects the model-visible portion of complete tool history. Holds no run state. */
final class ReviewHistory {

    /**
     * The Harness supplies complete Assistant/ToolResponse pairs and the limits for this call.
     * Stored messages are not modified; only the returned ToolResponse copies are shortened.
     */
    List<Message> selectHistory(List<Message> completeHistory, int maxRounds,
            int maxToolResultChars) {
        Objects.requireNonNull(completeHistory);
        if (maxRounds <= 0 || maxToolResultChars <= 0) {
            throw new IllegalArgumentException("invalid history limits");
        }
        if (completeHistory.size() % 2 != 0) {
            throw new IllegalArgumentException("tool history must contain complete message pairs");
        }

        int start = (int) Math.max(0L, completeHistory.size() - 2L * maxRounds);
        List<Message> visible = new ArrayList<>(completeHistory.size() - start);
        for (int index = start; index < completeHistory.size(); index += 2) {
            if (!(completeHistory.get(index) instanceof AssistantMessage assistant)
                    || !(completeHistory.get(index + 1) instanceof ToolResponseMessage response)) {
                throw new IllegalArgumentException("tool history must alternate Assistant and ToolResponse");
            }
            ToolResponseMessage limited = limitResponse(response, maxToolResultChars);
            visible.add(assistant);
            visible.add(limited);
        }
        return List.copyOf(visible);
    }

    private ToolResponseMessage limitResponse(ToolResponseMessage raw, int maxChars) {
        List<ToolResponseMessage.ToolResponse> responses = new ArrayList<>();
        for (ToolResponseMessage.ToolResponse result : raw.getResponses()) {
            String content = result.responseData() == null ? "" : result.responseData();
            if (content.length() > maxChars) {
                content = content.substring(0, maxChars)
                        + "\n[tool result truncated: showing first " + maxChars + " of "
                        + content.length() + " characters; last line may be incomplete]";
            }
            responses.add(new ToolResponseMessage.ToolResponse(result.id(), result.name(), content));
        }
        return ToolResponseMessage.builder().responses(responses).metadata(raw.getMetadata()).build();
    }

}

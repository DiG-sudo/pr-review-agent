package com.guodi.pragent.harness;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;

/** Selects the model-visible portion of complete tool history. Holds no run state. */
final class ToolHistorySelector {

    /**
     * History includes complete tool pairs, ordinary text responses and continuation messages.
     * Stored messages are not modified; only the returned ToolResponse copies are shortened.
     */
    List<Message> selectRecentRounds(List<Message> completeHistory, int maxRounds, int maxToolResultChars) {
        Objects.requireNonNull(completeHistory);
        if (maxRounds <= 0 || maxToolResultChars <= 0) {
            throw new IllegalArgumentException("invalid history limits");
        }
        List<List<Message>> groups = new ArrayList<>();
        for (int index = 0; index < completeHistory.size(); index++) {
            Message message = completeHistory.get(index);
            if (message instanceof AssistantMessage assistant && !assistant.getToolCalls().isEmpty()) {
                if (index + 1 >= completeHistory.size() || !(completeHistory.get(index + 1) instanceof ToolResponseMessage response)) {
                    throw new IllegalArgumentException("tool history must contain complete message pairs");
                }
                if (assistant.getToolCalls().size() != response.getResponses().size()) {
                    throw new IllegalArgumentException("tool calls and responses must match");
                }
                for (int call = 0; call < assistant.getToolCalls().size(); call++) {
                    var intent = assistant.getToolCalls().get(call);
                    var result = response.getResponses().get(call);
                    if (!intent.id().equals(result.id()) || !intent.name().equals(result.name())) {
                        throw new IllegalArgumentException("tool calls and responses must match");
                    }
                }
                groups.add(List.of(assistant, limitResponse(response, maxToolResultChars)));
                index++;
            } else if (message instanceof ToolResponseMessage) {
                throw new IllegalArgumentException("tool response has no matching assistant call");
            } else {
                groups.add(List.of(message));
            }
        }
        List<Message> visible = new ArrayList<>();
        for (int index = Math.max(0, groups.size() - maxRounds); index < groups.size(); index++) {
            visible.addAll(groups.get(index));
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

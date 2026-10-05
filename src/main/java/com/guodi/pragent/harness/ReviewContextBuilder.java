package com.guodi.pragent.harness;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.guodi.pragent.reviewer.Finding;

/**
 * Builds one model-visible message list. The Harness owns recovery decisions and supplies
 * committed tool history and the current Finding snapshot; this class retains no run state.
 */
@Component
public final class ReviewContextBuilder {

    public record Input(List<Message> initialMessages, List<Message> completeHistory,List<Finding> findingsSnapshot, int maxRounds, int maxToolResultChars) {

        public Input {
            initialMessages = List.copyOf(initialMessages);
            if (initialMessages.isEmpty()) {
                throw new IllegalArgumentException("initial messages are required");
            }
            completeHistory = List.copyOf(completeHistory);
            findingsSnapshot = List.copyOf(findingsSnapshot);
            if (maxRounds <= 0 || maxToolResultChars <= 0) {
                throw new IllegalArgumentException("context limits must be positive");
            }
        }
    }

    private final ToolHistorySelector history = new ToolHistorySelector();
    private final ObjectMapper objectMapper;

    public ReviewContextBuilder(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper);
    }

    /** The only context entry point used by the Harness, for both new and resumed runs. */
    public List<Message> buildModelMessages(Input input) {
        Objects.requireNonNull(input);

        List<Message> messages = new ArrayList<>(input.initialMessages());
        messages.addAll(history.selectRecentRounds(
                input.completeHistory(), input.maxRounds(), input.maxToolResultChars()));
        messages.add(new UserMessage("Current recorded Findings (authoritative state):\n"
                + findingsJson(input.findingsSnapshot())));
        return List.copyOf(messages);
    }

    private String findingsJson(List<Finding> findings) {
        try {
            return objectMapper.writeValueAsString(findings);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("cannot serialize current Findings", error);
        }
    }
}

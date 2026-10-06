package com.guodi.pragent.harness;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.guodi.pragent.runtime.ReviewExecution;

/** 从统一执行数据构造本轮可见消息，不修改完整历史。 */
@Component
public final class ReviewContextBuilder {

    private final ToolHistorySelector history = new ToolHistorySelector();
    private final ObjectMapper objectMapper;
    private final int maxRounds;
    private final int maxToolResultChars;

    public ReviewContextBuilder(ObjectMapper objectMapper, @Value("${pr-review.context.max-rounds:10}") int maxRounds, @Value("${pr-review.context.max-tool-result-chars:12000}") int maxToolResultChars) {
        this.objectMapper = Objects.requireNonNull(objectMapper);
        if (maxRounds <= 0 || maxToolResultChars <= 0) {
            throw new IllegalArgumentException("context limits must be positive");
        }
        this.maxRounds = maxRounds;
        this.maxToolResultChars = maxToolResultChars;
    }

    public List<Message> buildModelMessages(ReviewExecution execution) {
        List<Message> messages = new ArrayList<>(execution.getInitialMessages());
        messages.addAll(history.selectRecentRounds(execution.getHistory(), maxRounds, maxToolResultChars));
        try {
            String findings = objectMapper.writeValueAsString(execution.getReviewState().findingsSnapshot());
            messages.add(new UserMessage("Current recorded Findings (authoritative state):\n" + findings));
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("cannot serialize current Findings", error);
        }
        return List.copyOf(messages);
    }
}

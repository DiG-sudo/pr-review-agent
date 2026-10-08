package com.guodi.pragent.harness;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.guodi.pragent.runtime.ReviewExecution;

/** 从统一执行数据构造本轮可见消息，不修改完整历史。 */
@Component
public final class ReviewContextBuilder {

    private final ObjectMapper objectMapper;

    public ReviewContextBuilder(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper);
    }

    public List<Message> buildModelMessages(ReviewExecution execution) {
        List<Message> messages = new ArrayList<>(execution.getInitialMessages());
        messages.addAll(execution.getHistory());
        try {
            String findings = objectMapper.writeValueAsString(execution.getReviewState().findingsSnapshot());
            messages.add(new UserMessage("Current recorded Findings (authoritative state):\n" + findings));
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("cannot serialize current Findings", error);
        }
        int remaining = execution.getMaxModelCalls() - execution.getModelCalls();
        messages.add(new UserMessage("""
                Execution budget:
                - current model call: %d of %d
                - calls remaining after this response: %d

                Complete the review within this budget. Do not start open-ended repository
                exploration. If every assigned file has received one focused review pass, call
                publish_review alone now. Zero Findings is a valid successful review.
                """.formatted(execution.getModelCalls(), execution.getMaxModelCalls(), remaining)));
        return List.copyOf(messages);
    }
}

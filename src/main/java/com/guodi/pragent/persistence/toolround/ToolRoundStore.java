package com.guodi.pragent.persistence.toolround;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.springframework.ai.chat.messages.AssistantMessage.ToolCall;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.guodi.pragent.persistence.reviewagent.ReviewAgentEntity;
import com.guodi.pragent.persistence.reviewagent.ReviewAgentMapper;
import com.guodi.pragent.persistence.reviewrun.StoredReviewState;
import com.guodi.pragent.persistence.toolround.StoredAssistantMessage.StoredToolCall;
import com.guodi.pragent.persistence.toolround.StoredToolResponseMessage.StoredToolResult;
import com.guodi.pragent.reviewer.ReviewState;
import com.guodi.pragent.runtime.tool.ToolOutcome;



/** 持久化工具调用意图；在同一事务中保存工具结果和审查状态快照。 */
@Component
public class ToolRoundStore{
    private final ToolRoundMapper toolRoundMapper;
    private final ReviewAgentMapper reviewAgentMapper;
    private final ObjectMapper objectMapper;
    public ToolRoundStore(ToolRoundMapper toolRoundMapper, ReviewAgentMapper reviewAgentMapper,
            ObjectMapper objectMapper){
        this.toolRoundMapper = toolRoundMapper;
        this.reviewAgentMapper = reviewAgentMapper;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public Long beginRound(Long agentId, int roundNumber,AssistantMessage assistantMessage){
        List<StoredToolCall> storedToolCalls = new ArrayList<>();
        //接收调用意图,存入数据库表
        for(ToolCall call : assistantMessage.getToolCalls()){
            storedToolCalls.add(new StoredToolCall(call.id(),call.type(),call.name(),call.arguments()));
        }
        StoredAssistantMessage storedAssistantMessage = new StoredAssistantMessage(assistantMessage.getText(), storedToolCalls);
        ToolRoundEntity round = new ToolRoundEntity();
        round.setAgentId(agentId);
        round.setRoundNumber(roundNumber);
        round.setStatus("OPEN");
        try {
            round.setAssistantMessageJson(objectMapper.writeValueAsString(storedAssistantMessage)
        );
        } catch (JsonProcessingException exception) {
           throw new IllegalStateException("failed to serialize tool round", exception);
        }
        toolRoundMapper.insert(round);
        return round.getId();
    }

    @Transactional
    public boolean completeRound(Long agentId, Long roundId, List<ToolOutcome> outcomes, ReviewState reviewState) {
        Objects.requireNonNull(agentId, "agentId cannot be null");
        Objects.requireNonNull(roundId, "roundId cannot be null");
        Objects.requireNonNull(outcomes, "outcomes cannot be null");
        Objects.requireNonNull(reviewState, "reviewState cannot be null");
        if (outcomes.isEmpty()) {
            throw new IllegalArgumentException("outcomes cannot be empty");
        }

        List<StoredToolResult> results = outcomes.stream()
                .map(outcome -> new StoredToolResult(
                        outcome.getCall().id(),
                        outcome.getCall().name(),
                        outcome.isSuccess(),
                        outcome.getToolResponse().responseData()))
                .toList();

        StoredReviewState state;
        synchronized (reviewState) {
            state = new StoredReviewState(reviewState.findingsSnapshot());
        }

        String responseJson = toJson(new StoredToolResponseMessage(results));
        String reviewStateJson = toJson(state);

        boolean completed = outcomes.size() == 1 && outcomes.getFirst().isSuccess()
                && "publish_review".equals(outcomes.getFirst().getCall().name());
        ToolRoundEntity roundUpdate = new ToolRoundEntity();
        roundUpdate.setStatus("COMPLETED");
        roundUpdate.setToolResponseJson(responseJson);
        int updatedRounds = toolRoundMapper.update(roundUpdate,
                Wrappers.<ToolRoundEntity>lambdaUpdate()
                        .eq(ToolRoundEntity::getId, roundId)
                        .eq(ToolRoundEntity::getAgentId, agentId)
                        .eq(ToolRoundEntity::getStatus, "OPEN"));
        if (updatedRounds != 1) {
            throw new IllegalStateException("tool round is missing or not OPEN: " + roundId);
        }

        ReviewAgentEntity agentUpdate = new ReviewAgentEntity();
        agentUpdate.setReviewStateJson(reviewStateJson);
        if (completed) {
            agentUpdate.setSuccess(true);
        }
        int updatedAgents = reviewAgentMapper.update(agentUpdate,
                Wrappers.<ReviewAgentEntity>lambdaUpdate()
                        .eq(ReviewAgentEntity::getId, agentId)
                        .isNull(ReviewAgentEntity::getSuccess));
        if (updatedAgents != 1) {
            throw new IllegalStateException("review Agent is missing or already finished: " + agentId);
        }
        return completed;
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("failed to serialize tool round", exception);
        }
    }

}

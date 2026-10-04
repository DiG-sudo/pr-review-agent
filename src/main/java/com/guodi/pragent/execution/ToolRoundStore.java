package com.guodi.pragent.execution;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.AssistantMessage.ToolCall;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.guodi.pragent.harness.tool.ToolOutcome;
import com.guodi.pragent.reviewer.state.ReviewState;
import com.guodi.pragent.tables.reviewrun.ReviewRunEntity;
import com.guodi.pragent.tables.reviewrun.ReviewRunMapper;
import com.guodi.pragent.tables.reviewrun.StoredReviewState;
import com.guodi.pragent.tables.toolround.StoredAssistantMessage;
import com.guodi.pragent.tables.toolround.StoredAssistantMessage.StoredToolCall;
import com.guodi.pragent.tables.toolround.StoredToolResponseMessage;
import com.guodi.pragent.tables.toolround.StoredToolResponseMessage.StoredToolResult;
import com.guodi.pragent.tables.toolround.ToolRoundEntity;
import com.guodi.pragent.tables.toolround.ToolRoundMapper;



/**
 * 工具执行逻辑闭环
 * ToolRoundStore
 */
@Component
public class ToolRoundStore{
    private final ToolRoundMapper toolRoundMapper;
    private final ReviewRunMapper reviewRunMapper;
    private final ObjectMapper objectMapper;
    public ToolRoundStore( ToolRoundMapper toolRoundMapper,ReviewRunMapper reviewRunMapper,ObjectMapper objectMapper){
        this.toolRoundMapper = toolRoundMapper;
        this.reviewRunMapper = reviewRunMapper;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public Long begin(Long runId,  int roundNumber,AssistantMessage assistantMessage){
        List<StoredToolCall> storedToolCalls = new ArrayList<>();
        //接收调用意图,存入数据库表
        for(ToolCall call : assistantMessage.getToolCalls()){
            storedToolCalls.add(new StoredToolCall(call.id(),call.type(),call.name(),call.arguments()));
        }
        StoredAssistantMessage storedAssistantMessage = new StoredAssistantMessage(assistantMessage.getText(), storedToolCalls);
        ToolRoundEntity round = new ToolRoundEntity();
        round.setRunId(runId);
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
    public void complete(Long runId, Long roundId, List<ToolOutcome> outcomes, ReviewState reviewState) {
        Objects.requireNonNull(runId, "runId cannot be null");
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
            state = new StoredReviewState(reviewState.published(), reviewState.findingsSnapshot());
        }

        String responseJson = toJson(new StoredToolResponseMessage(results));
        String reviewStateJson = toJson(state);

        ToolRoundEntity roundUpdate = new ToolRoundEntity();
        roundUpdate.setStatus("COMPLETED");
        roundUpdate.setToolResponseJson(responseJson);
        int updatedRounds = toolRoundMapper.update(roundUpdate,
                Wrappers.<ToolRoundEntity>lambdaUpdate()
                        .eq(ToolRoundEntity::getId, roundId)
                        .eq(ToolRoundEntity::getRunId, runId)
                        .eq(ToolRoundEntity::getStatus, "OPEN"));
        if (updatedRounds != 1) {
            throw new IllegalStateException("tool round is missing or not OPEN: " + roundId);
        }

        ReviewRunEntity runUpdate = new ReviewRunEntity();
        runUpdate.setReviewStateJson(reviewStateJson);
        int updatedRuns = reviewRunMapper.update(runUpdate,
                Wrappers.<ReviewRunEntity>lambdaUpdate()
                        .eq(ReviewRunEntity::getId, runId)
                        .eq(ReviewRunEntity::getStatus, "RUNNING"));
        if (updatedRuns != 1) {
            throw new IllegalStateException("review run is missing or not RUNNING: " + runId);
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("failed to serialize tool round", exception);
        }
    }

}

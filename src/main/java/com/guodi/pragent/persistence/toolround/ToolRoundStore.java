package com.guodi.pragent.persistence.toolround;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.springframework.ai.chat.messages.AssistantMessage.ToolCall;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.guodi.pragent.persistence.reviewrun.ReviewRunEntity;
import com.guodi.pragent.persistence.reviewrun.ReviewRunMapper;
import com.guodi.pragent.persistence.reviewrun.StoredReviewState;
import com.guodi.pragent.persistence.toolround.StoredAssistantMessage.StoredToolCall;
import com.guodi.pragent.persistence.toolround.StoredToolResponseMessage.StoredToolResult;
import com.guodi.pragent.reviewer.ReviewState;
import com.guodi.pragent.runtime.tool.ToolOutcome;
import com.guodi.pragent.runtime.ReviewStatus;



/** 持久化工具调用意图；在同一事务中保存工具结果和审查状态快照。 */
@Component
public class ToolRoundStore{
    private final ToolRoundMapper toolRoundMapper;
    private final ReviewRunMapper reviewRunMapper;
    private final ObjectMapper objectMapper;
    public ToolRoundStore(ToolRoundMapper toolRoundMapper,ReviewRunMapper reviewRunMapper,ObjectMapper objectMapper){
        this.toolRoundMapper = toolRoundMapper;
        this.reviewRunMapper = reviewRunMapper;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public Long beginRound(Long runId, int roundNumber,AssistantMessage assistantMessage){
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
    public void completeRound(Long runId, Long roundId, List<ToolOutcome> outcomes, ReviewState reviewState) {
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
            state = new StoredReviewState(reviewState.findingsSnapshot());
        }

        String responseJson = toJson(new StoredToolResponseMessage(results));
        String reviewStateJson = toJson(state);

        boolean publicationReady = outcomes.size() == 1 && outcomes.getFirst().isSuccess()
                && "publish_review".equals(outcomes.getFirst().getCall().name());
        ToolRoundEntity roundUpdate = new ToolRoundEntity();
        if (publicationReady) {
            roundUpdate.setPublicationPayloadJson(toJson(Map.of("body", outcomes.getFirst().getToolResponse().responseData())));
        }
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
        if (publicationReady) {
            runUpdate.setStatus(ReviewStatus.PUBLICATION_READY.name());
        }
        int updatedRuns = reviewRunMapper.update(runUpdate,
                Wrappers.<ReviewRunEntity>lambdaUpdate()
                        .eq(ReviewRunEntity::getId, runId)
                        .eq(ReviewRunEntity::getStatus, ReviewStatus.RUNNING.name()));
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

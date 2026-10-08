package com.guodi.pragent.persistence.reviewagent;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.ai.chat.messages.Message;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.guodi.pragent.persistence.reviewrun.ReviewRunEntity;
import com.guodi.pragent.persistence.reviewrun.ReviewRunMapper;
import com.guodi.pragent.persistence.reviewrun.StoredReviewState;
import com.guodi.pragent.runtime.ReviewStatus;

/** Transactional persistence for the fixed Agent plan and parent-run transitions. */
@Component
public class ReviewAgentStore {

    private final ReviewAgentMapper agents;
    private final ReviewRunMapper runs;
    private final ObjectMapper json;

    public ReviewAgentStore(ReviewAgentMapper agents, ReviewRunMapper runs, ObjectMapper json) {
        this.agents = agents;
        this.runs = runs;
        this.json = json;
    }

    public List<ReviewAgentEntity> findByRunId(Long runId) {
        return agents.selectList(Wrappers.<ReviewAgentEntity>lambdaQuery()
                .eq(ReviewAgentEntity::getRunId, runId)
                .orderByAsc(ReviewAgentEntity::getAgentIndex));
    }

    /** Persists every Agent before the first review-model call. */
    @Transactional
    public List<ReviewAgentEntity> createPlan(Long runId, List<AgentPlan> plan, int maxModelCalls) {
        List<ReviewAgentEntity> existing = findByRunId(runId);
        if (!existing.isEmpty()) {
            return existing;
        }
        if (plan.isEmpty() || plan.size() > 4 || maxModelCalls <= 0) {
            throw new IllegalArgumentException("Agent plan must contain 1-4 Agents with a positive budget");
        }

        String emptyState = toJson(new StoredReviewState(List.of()));
        List<ReviewAgentEntity> created = new ArrayList<>(plan.size());
        for (int index = 0; index < plan.size(); index++) {
            AgentPlan item = plan.get(index);
            List<Message> messages = item.initialMessages();
            if (messages.isEmpty()) {
                throw new IllegalArgumentException("Agent initial messages cannot be empty");
            }
            ReviewAgentEntity agent = new ReviewAgentEntity();
            agent.setRunId(runId);
            agent.setAgentIndex(index);
            agent.setFilePathsJson(toJson(item.filePaths()));
            agent.setInitialMessagesJson(toJson(messages.stream()
                    .map(message -> Map.of(
                            "type", message.getMessageType().getValue(),
                            "text", message.getText()))
                    .toList()));
            agent.setReviewStateJson(emptyState);
            agent.setModelCalls(0);
            agent.setMaxModelCalls(maxModelCalls);
            agents.insert(agent);
            created.add(agent);
        }
        return List.copyOf(created);
    }

    /** Reserves one call from the current Agent's own persisted budget. */
    public int reserveModelCall(Long runId, Long agentId, int currentCalls, int maximumCalls) {
        if (currentCalls >= maximumCalls) {
            throw new com.guodi.pragent.runtime.AgentRunFailedException("Model call budget exhausted");
        }
        int nextCalls = currentCalls + 1;
        ReviewAgentEntity update = new ReviewAgentEntity();
        update.setModelCalls(nextCalls);
        int changed = agents.update(update, Wrappers.<ReviewAgentEntity>lambdaUpdate()
                .eq(ReviewAgentEntity::getId, agentId)
                .eq(ReviewAgentEntity::getRunId, runId)
                .isNull(ReviewAgentEntity::getSuccess)
                .eq(ReviewAgentEntity::getModelCalls, currentCalls)
                .eq(ReviewAgentEntity::getMaxModelCalls, maximumCalls));
        if (changed != 1) {
            throw new IllegalStateException("Agent 模型调用次数未保存: " + agentId);
        }
        return nextCalls;
    }

    @Transactional
    public void failAgentAndRun(Long runId, Long agentId, String reason) {
        ReviewAgentEntity agentUpdate = new ReviewAgentEntity();
        agentUpdate.setSuccess(false);
        int changedAgent = agents.update(agentUpdate, Wrappers.<ReviewAgentEntity>lambdaUpdate()
                .eq(ReviewAgentEntity::getId, agentId)
                .eq(ReviewAgentEntity::getRunId, runId)
                .isNull(ReviewAgentEntity::getSuccess));
        if (changedAgent != 1) {
            throw new IllegalStateException("Agent 失败结果未保存: " + agentId);
        }
        failRun(runId, reason);
    }

    @Transactional
    public void failRun(Long runId, String reason) {
        ReviewRunEntity update = new ReviewRunEntity();
        update.setStatus(ReviewStatus.FAILED.name());
        update.setFinalResultJson(toJson(Map.of("reason", reason)));
        int changed = runs.update(update, Wrappers.<ReviewRunEntity>lambdaUpdate()
                .eq(ReviewRunEntity::getId, runId)
                .eq(ReviewRunEntity::getStatus, ReviewStatus.RUNNING.name()));
        if (changed != 1) {
            throw new IllegalStateException("任务失败状态未保存: " + runId);
        }
    }

    /** Saves the aggregate body and parent READY transition in one transaction. */
    @Transactional
    public ReviewRunEntity markPublicationReady(Long runId, String aggregateStateJson,
            String publicationPayloadJson) {
        List<ReviewAgentEntity> plan = findByRunId(runId);
        if (plan.isEmpty() || plan.stream().anyMatch(agent -> !Boolean.TRUE.equals(agent.getSuccess()))) {
            throw new IllegalStateException("Agent 计划尚未全部完成: " + runId);
        }
        ReviewRunEntity update = new ReviewRunEntity();
        update.setReviewStateJson(aggregateStateJson);
        update.setPublicationPayloadJson(publicationPayloadJson);
        update.setStatus(ReviewStatus.PUBLICATION_READY.name());
        int changed = runs.update(update, Wrappers.<ReviewRunEntity>lambdaUpdate()
                .eq(ReviewRunEntity::getId, runId)
                .eq(ReviewRunEntity::getStatus, ReviewStatus.RUNNING.name()));
        if (changed != 1) {
            throw new IllegalStateException("发布准备状态未保存: " + runId);
        }
        ReviewRunEntity current = runs.selectById(runId);
        if (current == null) {
            throw new IllegalStateException("审查任务不存在: " + runId);
        }
        return current;
    }

    private String toJson(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("Agent 记录无法序列化", error);
        }
    }

    public record AgentPlan(List<Message> initialMessages, List<String> filePaths) {
        public AgentPlan {
            initialMessages = List.copyOf(initialMessages);
            filePaths = List.copyOf(filePaths);
            if (initialMessages.isEmpty()) {
                throw new IllegalArgumentException("Agent plan requires initial messages");
            }
        }
    }
}

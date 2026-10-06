package com.guodi.pragent.harness;

import java.util.ArrayList;
import java.util.List;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.guodi.pragent.persistence.reviewrun.ReviewRunEntity;
import com.guodi.pragent.persistence.reviewrun.ReviewRunMapper;
import com.guodi.pragent.persistence.reviewrun.StoredReviewState;
import com.guodi.pragent.persistence.toolround.StoredAssistantMessage;
import com.guodi.pragent.persistence.toolround.StoredToolResponseMessage;
import com.guodi.pragent.persistence.toolround.ToolRoundEntity;
import com.guodi.pragent.persistence.toolround.ToolRoundMapper;
import com.guodi.pragent.runtime.ReviewExecution;
import com.guodi.pragent.runtime.ReviewStatus;

/** 只恢复 RUNNING 的本地已提交工具历史和 Findings；任务分流和远端发布归 Harness。 */
@Component
public class ReviewRunRestorer {
    private final ReviewRunMapper reviews;
    private final ToolRoundMapper rounds;
    private final ObjectMapper json;
    private final TransactionTemplate transactions;

    public ReviewRunRestorer(ReviewRunMapper reviews, ToolRoundMapper rounds, ObjectMapper json, TransactionTemplate transactions) {
        this.reviews = reviews;
        this.rounds = rounds;
        this.json = json;
        this.transactions = transactions;
    }

    /** 初始消息、工作区和累计预算由 aroundRun 载入；这里不创建另一套恢复结果。 */
    public void restore(ReviewExecution execution) {
        ReviewRunEntity task = reviews.selectById(execution.getRunId());
        if (task == null || !ReviewStatus.RUNNING.name().equals(task.getStatus())) {
            throw new IllegalStateException("只有 RUNNING 任务可以恢复: " + execution.getRunId());
        }
        if (task.getInitialMessagesJson() == null || task.getReviewStateJson() == null) {
            throw new IllegalStateException("任务尚无完整初始化记录: " + task.getId());
        }
        List<ToolRoundEntity> storedRounds = rounds.selectList(Wrappers.<ToolRoundEntity>lambdaQuery().eq(ToolRoundEntity::getRunId, task.getId()).orderByAsc(ToolRoundEntity::getRoundNumber));
        List<Message> history = new ArrayList<>();
        StoredReviewState state;
        try {
            state = json.readValue(task.getReviewStateJson(), StoredReviewState.class);
            for (ToolRoundEntity round : storedRounds) {
                if (!"COMPLETED".equals(round.getStatus())) {
                    if (!"OPEN".equals(round.getStatus()) && !"ABANDONED".equals(round.getStatus())) {
                        throw new IllegalStateException("未知工具轮状态: " + round.getStatus());
                    }
                    continue;
                }
                StoredAssistantMessage assistant = json.readValue(round.getAssistantMessageJson(), StoredAssistantMessage.class);
                StoredToolResponseMessage response = json.readValue(round.getToolResponseJson(), StoredToolResponseMessage.class);
                if (assistant.toolCalls().size() != response.responses().size()) {
                    throw new IllegalStateException("工具调用和响应数量不一致: " + round.getId());
                }
                for (int index = 0; index < assistant.toolCalls().size(); index++) {
                    var call = assistant.toolCalls().get(index);
                    var result = response.responses().get(index);
                    if (!call.id().equals(result.callId()) || !call.name().equals(result.name())) {
                        throw new IllegalStateException("工具调用和响应身份不一致: " + round.getId());
                    }
                }
                history.add(AssistantMessage.builder().content(assistant.text() == null ? "" : assistant.text()).toolCalls(assistant.toolCalls().stream().map(call -> new AssistantMessage.ToolCall(call.id(), call.type(), call.name(), call.arguments())).toList()).build());
                history.add(ToolResponseMessage.builder().responses(response.responses().stream().map(result -> new ToolResponseMessage.ToolResponse(result.callId(), result.name(), result.content())).toList()).build());
            }
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("审查恢复记录无法解析", error);
        }
        transactions.executeWithoutResult(transaction -> {
            for (ToolRoundEntity round : storedRounds) {
                if ("OPEN".equals(round.getStatus())) {
                    ToolRoundEntity update = new ToolRoundEntity();
                    update.setStatus("ABANDONED");
                    int changed = rounds.update(update, Wrappers.<ToolRoundEntity>lambdaUpdate().eq(ToolRoundEntity::getId, round.getId()).eq(ToolRoundEntity::getStatus, "OPEN"));
                    if (changed != 1) {
                        throw new IllegalStateException("未闭合工具轮已改变: " + round.getId());
                    }
                }
            }
        });
        execution.getReviewState().restoreFindings(state.findings());
        execution.getHistory().clear();
        execution.getHistory().addAll(history);
        execution.setNextToolRoundNumber(storedRounds.isEmpty() ? 1 : storedRounds.getLast().getRoundNumber() + 1);
        // TODO: 普通文本响应及继续提示的持久化和恢复，需要单独接入。
        // TODO: 累计模型预算尚无数据库字段；接入前不能宣称恢复预算已完整实现。
    }
}

package com.guodi.pragent.harness;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.guodi.pragent.persistence.reviewagent.ReviewAgentEntity;
import com.guodi.pragent.persistence.reviewrun.ReviewRunEntity;
import com.guodi.pragent.persistence.reviewrun.StoredReviewState;
import com.guodi.pragent.persistence.toolround.StoredAssistantMessage;
import com.guodi.pragent.persistence.toolround.StoredToolResponseMessage;
import com.guodi.pragent.persistence.toolround.ToolRoundEntity;
import com.guodi.pragent.persistence.toolround.ToolRoundMapper;
import com.guodi.pragent.runtime.ReviewExecution;
import com.guodi.pragent.preparation.GitHubWorkspacePreparer.ReviewWorkspace;
import com.guodi.pragent.preparation.GitHubWorkspacePreparer.FileDiff;
import com.guodi.pragent.reviewer.ReviewState;
import com.guodi.pragent.reviewer.tool.ReviewToolContext;

/** 只恢复 RUNNING 的本地已提交工具历史和 Findings；任务分流和远端发布归 Harness。 */
@Component
public class ReviewRunRestorer {
    private final ToolRoundMapper rounds;
    private final ObjectMapper json;
    private final TransactionTemplate transactions;

    public ReviewRunRestorer(ToolRoundMapper rounds, ObjectMapper json, TransactionTemplate transactions) {
        this.rounds = rounds;
        this.json = json;
        this.transactions = transactions;
    }

    /** 加载初始消息、工具历史和 Findings，创建本次恢复执行的上下文。 */
    public ReviewExecution loadExecution(ReviewRunEntity task, ReviewAgentEntity agent,
            ReviewWorkspace workspace) {
        if (!task.getId().equals(agent.getRunId()) || agent.getSuccess() != null) {
            throw new IllegalStateException("Agent 不属于当前任务或已结束: " + agent.getId());
        }
        List<ToolRoundEntity> storedRounds = rounds.selectList(
                Wrappers.<ToolRoundEntity>lambdaQuery()
                        .eq(ToolRoundEntity::getAgentId, agent.getId())
                        .orderByAsc(ToolRoundEntity::getRoundNumber));
        List<Message> history = new ArrayList<>();
        StoredReviewState state;
        List<Message> initialMessages = new ArrayList<>();
        List<String> filePaths;
        try {
            for (var message : json.readTree(agent.getInitialMessagesJson())) {
                initialMessages.add(switch (message.get("type").asText()) {
                    case "system" -> new SystemMessage(message.get("text").asText());
                    case "user" -> new UserMessage(message.get("text").asText());
                    default -> throw new IllegalStateException("不支持的初始消息类型");
                });
            }
            state = json.readValue(agent.getReviewStateJson(), StoredReviewState.class);
            filePaths = agent.getFilePathsJson() == null
                    ? workspace.fileDiffs().stream().map(FileDiff::path).toList()
                    : json.readerForListOf(String.class).readValue(agent.getFilePathsJson());
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
        ReviewState reviewState = new ReviewState(task.getThreadId());
        reviewState.restoreFindings(state.findings());
        String scopedDiff = scopedDiff(workspace.fileDiffs(), filePaths);
        ToolContext toolContext = new ToolContext(Map.of(ReviewToolContext.KEY,
                new ReviewToolContext(workspace.workspaceDirectory(), reviewState, scopedDiff)));
        int nextRound = storedRounds.isEmpty() ? 1 : storedRounds.getLast().getRoundNumber() + 1;
        if (agent.getModelCalls() == null || agent.getMaxModelCalls() == null) {
            throw new IllegalStateException("Agent 模型预算未初始化: " + agent.getId());
        }
        // 普通文本及继续提示仅保留在运行内存中，恢复时只还原已完成工具轮次。
        return new ReviewExecution(task.getId(), agent.getId(), initialMessages, history, toolContext,
                agent.getModelCalls(), agent.getMaxModelCalls(), nextRound);
    }

    private static String scopedDiff(List<FileDiff> workspaceFiles, List<String> filePaths) {
        Map<String, FileDiff> byPath = new LinkedHashMap<>();
        for (FileDiff file : workspaceFiles) {
            byPath.put(file.path(), file);
        }
        StringBuilder diff = new StringBuilder();
        for (String path : filePaths) {
            FileDiff file = byPath.get(path);
            if (file == null) {
                throw new IllegalStateException("Agent 分组文件不在当前 PR diff 中: " + path);
            }
            if (file.patch() != null) {
                diff.append(file.patch());
                if (!file.patch().endsWith("\n")) {
                    diff.append('\n');
                }
            }
        }
        return diff.toString();
    }
}

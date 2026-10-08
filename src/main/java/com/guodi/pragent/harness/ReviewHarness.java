package com.guodi.pragent.harness;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.stereotype.Component;


import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.guodi.pragent.persistence.reviewagent.ReviewAgentEntity;
import com.guodi.pragent.persistence.reviewagent.ReviewAgentStore;
import com.guodi.pragent.persistence.reviewagent.ReviewAgentStore.AgentPlan;
import com.guodi.pragent.persistence.reviewrun.StoredReviewState;
import com.guodi.pragent.preparation.ReviewInitialMessagesBuilder;
import com.guodi.pragent.preparation.ReviewPlanGenerator;
import com.guodi.pragent.preparation.GitHubWorkspacePreparer;
import com.guodi.pragent.reviewer.GitHubReviewLookup;
import com.guodi.pragent.reviewer.GitHubReviewPublisher;
import com.guodi.pragent.reviewer.ReviewRequest;
import com.guodi.pragent.reviewer.ReviewState;
import com.guodi.pragent.reviewer.Finding;
import com.guodi.pragent.persistence.reviewrun.ReviewRunEntity;
import com.guodi.pragent.persistence.reviewrun.ReviewRunMapper;
import com.guodi.pragent.preparation.GitHubWorkspacePreparer.ReviewWorkspace;
import com.guodi.pragent.runtime.ReviewExecution;
import com.guodi.pragent.runtime.AgentRunResult;
import com.guodi.pragent.runtime.ReviewRunResult;
import com.guodi.pragent.runtime.ReviewStatus;
import com.guodi.pragent.runtime.tool.ToolRoundResult;
import com.guodi.pragent.runtime.tool.ToolRegistry;

/** 任务生命周期、推理准备、模型调用和工具轮的插入点。 */
@Component
public class ReviewHarness {

    private final ReviewRunMapper reviewRunMapper;
    private final ToolRoundCoordinator toolRoundCoordinator;
    private final ReviewContextBuilder reviewContextBuilder;
    private final ToolRegistry toolRegistry;
    private final ObjectMapper objectMapper;
    private final ReviewAgentStore reviewAgentStore;
    private final ReviewPlanGenerator reviewPlanGenerator;
    private final ReviewRunRestorer reviewRunRestorer;
    private final GitHubWorkspacePreparer workspacePreparer;
    private final GitHubReviewLookup reviewLookup;
    private final GitHubReviewPublisher reviewPublisher;
    private final ReviewInitialMessagesBuilder initialMessagesBuilder = new ReviewInitialMessagesBuilder();
    @Value("${pr-review.context.max-initial-diff-chars:30000}")
    private int maxInitialDiffChars = 30000;
    @Value("${pr-review.execution.max-model-calls:20}")
    private int maxModelCalls = 20;
    public ReviewHarness(ReviewRunMapper reviewRunMapper,ToolRoundCoordinator toolRoundCoordinator,ReviewContextBuilder reviewContextBuilder, ToolRegistry toolRegistry, ObjectMapper objectMapper,
            ReviewAgentStore reviewAgentStore, ReviewPlanGenerator reviewPlanGenerator,
            ReviewRunRestorer reviewRunRestorer, GitHubWorkspacePreparer workspacePreparer,
            GitHubReviewLookup reviewLookup, GitHubReviewPublisher reviewPublisher) {
        this.reviewRunMapper = reviewRunMapper;
        this.toolRoundCoordinator = toolRoundCoordinator;
        this.reviewContextBuilder = reviewContextBuilder;
        this.toolRegistry = toolRegistry;
        this.objectMapper = objectMapper;
        this.reviewAgentStore = reviewAgentStore;
        this.reviewPlanGenerator = reviewPlanGenerator;
        this.reviewRunRestorer = reviewRunRestorer;
        this.workspacePreparer = workspacePreparer;
        this.reviewLookup = reviewLookup;
        this.reviewPublisher = reviewPublisher;
    }

    /** next 是 Runtime.runLoop；正常返回必须表示已有终态或后续调度已可靠落库。 */
    public ReviewRunResult aroundRun(Long taskId, Function<ReviewExecution, AgentRunResult> next) {
        // before：任务分流；只有 RUNNING 需要工作区和执行上下文。
        ReviewRunEntity task = reviewRunMapper.selectById(taskId);
        if (task == null) {
            throw new IllegalStateException("审查任务不存在: " + taskId);
        }
        ReviewStatus status = ReviewStatus.valueOf(task.getStatus());
        switch (status) {
            case PUBLICATION_READY:
                return publishAndComplete(task);
            case PUBLISHED, FAILED:
                return new ReviewRunResult(status);
            case PENDING:
                throw new IllegalStateException("审查任务尚未被认领: " + taskId);
            case RUNNING:
                break;
        }

        ReviewRequest request = new ReviewRequest(task.getThreadId(), task.getRepository(),
                task.getPullRequestNumber(), task.getHeadSha(), task.getBaseSha(), null);
        ReviewRunEntity readyTask;
        try (ReviewWorkspace workspace = workspacePreparer.prepareWorkspace(request)) {
            List<ReviewAgentEntity> agents = loadOrCreateAgentPlan(task, request, workspace);

            for (ReviewAgentEntity agent : agents) {
                if (Boolean.TRUE.equals(agent.getSuccess())) {
                    continue;
                }
                if (Boolean.FALSE.equals(agent.getSuccess())) {
                    throw new IllegalStateException("RUNNING 任务包含失败 Agent: " + agent.getId());
                }

                ReviewRunEntity currentTask = reviewRunMapper.selectById(taskId);
                if (currentTask == null || !ReviewStatus.RUNNING.name().equals(currentTask.getStatus())) {
                    throw new IllegalStateException("任务不再允许执行 Agent: " + taskId);
                }
                ReviewExecution execution = reviewRunRestorer.loadExecution(currentTask, agent, workspace);
                AgentRunResult result = next.apply(execution);
                if (!result.success()) {
                    reviewAgentStore.failAgentAndRun(taskId, agent.getId(), result.failureReason());
                    return new ReviewRunResult(ReviewStatus.FAILED);
                }
            }
            readyTask = aggregateAndMarkReady(task);
        } catch (IOException error) {
            throw new UncheckedIOException("工作区清理失败: " + taskId, error);
        }
        return publishAndComplete(readyTask);
    }

    /** 远端发布已确认且本地更新成功后，才返回 PUBLISHED。 */
    private ReviewRunResult publishAndComplete(ReviewRunEntity task) {
        try {
            var published = reviewLookup.findPublished(task.getRepository(), task.getPullRequestNumber(),
                    task.getHeadSha(), task.getPublicationKey());
            long reviewId;
            if (published.isPresent()) {
                reviewId = published.getAsLong();
            } else {
                String body = objectMapper.readTree(task.getPublicationPayloadJson())
                        .required("body").asText();
                reviewId = reviewPublisher.publish(task.getRepository(), task.getPullRequestNumber(),
                        task.getHeadSha(), task.getPublicationKey(), body);
            }
            ReviewRunEntity update = new ReviewRunEntity();
            update.setExternalReviewId(Long.toString(reviewId));
            update.setStatus(ReviewStatus.PUBLISHED.name());
            int changed = reviewRunMapper.update(update, Wrappers.<ReviewRunEntity>lambdaUpdate()
                    .eq(ReviewRunEntity::getId, task.getId())
                    .eq(ReviewRunEntity::getStatus, ReviewStatus.PUBLICATION_READY.name()));
            if (changed != 1) {
                throw new IllegalStateException("发布结果未保存: " + task.getId());
            }
            return new ReviewRunResult(ReviewStatus.PUBLISHED);
        } catch (IOException error) {
            throw new UncheckedIOException("发布或核对失败: " + task.getId(), error);
        }
    }

    /** next 是 modelHandler；返回一次经过预算预占和有效性校验的模型响应。 */
    public ChatResponse aroundReasoning(ReviewExecution execution, Function<Prompt, ChatResponse> next) {
        ReviewRunEntity task = reviewRunMapper.selectById(execution.getReviewRunId());
        if (task == null) {
            throw new IllegalStateException("审查任务不存在: " + execution.getReviewRunId());
        }
        ReviewStatus status = ReviewStatus.valueOf(task.getStatus());
        if (status != ReviewStatus.RUNNING) {
            throw new IllegalStateException("任务状态不允许推理: " + status);
        }
        int modelCalls = reviewAgentStore.reserveModelCall(
                execution.getReviewRunId(), execution.getAgentId(),
                execution.getModelCalls(), execution.getMaxModelCalls());
        List<Message> modelMessages = reviewContextBuilder.buildModelMessages(execution);
        ToolCallingChatOptions options = ToolCallingChatOptions
                                        .builder()
                                        .toolCallbacks(toolRegistry.getCallbacks())
                                        .internalToolExecutionEnabled(false)
                                        .build();
        Prompt prompt = new Prompt(modelMessages,options);
        // 请求前预占次数，避免模型已调用但异常退出时恢复预算归零。
        execution.setModelCalls(modelCalls);

        return next.apply(prompt);
    }

    /** next 是实际的 chatModel.call，保持 Spring AI 的输入输出协议。 */
    public ChatResponse aroundModelCall(Prompt prompt, Function<Prompt, ChatResponse> next) {
        //before 预留逻辑
        ChatResponse response = next.apply(prompt);
        if (response == null || response.getResult() == null || response.getResult().getOutput() == null) {
            throw new IllegalStateException("模型没有返回有效响应");
        }
        // TODO: after 模型请求的超时、重试和调用记录策略。
        return response;
    }

    /** 完整工具轮次的执行和持久化由 Coordinator 负责，异常向外传播。 */
    public ToolRoundResult aroundToolRound(ReviewExecution execution,AssistantMessage assistantMessage) {
        return toolRoundCoordinator.executeAndPersist(execution, assistantMessage);
    }

    /** Existing rows mean recovery; only a new run is grouped and persisted here. */
    private List<ReviewAgentEntity> loadOrCreateAgentPlan(ReviewRunEntity task, ReviewRequest request,
            ReviewWorkspace workspace) {
        List<ReviewAgentEntity> existing = reviewAgentStore.findByRunId(task.getId());
        if (!existing.isEmpty()) {
            return existing;
        }
        var groups = reviewPlanGenerator.generate(workspace.fileDiffs());
        List<AgentPlan> plan = groups.stream()
                .map(group -> new AgentPlan(
                        initialMessagesBuilder.buildInitialMessages(request, group, maxInitialDiffChars),
                        group.stream().map(GitHubWorkspacePreparer.FileDiff::path).toList()))
                .toList();
        return reviewAgentStore.createPlan(task.getId(), plan, maxModelCalls);
    }

    private ReviewRunEntity aggregateAndMarkReady(ReviewRunEntity task) {
        List<Finding> findings = new ArrayList<>();
        for (ReviewAgentEntity agent : reviewAgentStore.findByRunId(task.getId())) {
            if (!Boolean.TRUE.equals(agent.getSuccess())) {
                throw new IllegalStateException("Agent 计划尚未全部完成: " + task.getId());
            }
            try {
                findings.addAll(objectMapper.readValue(
                        agent.getReviewStateJson(), StoredReviewState.class).findings());
            } catch (JsonProcessingException error) {
                throw new IllegalStateException("Agent Findings 无法解析: " + agent.getId(), error);
            }
        }
        ReviewState aggregate = new ReviewState(task.getThreadId());
        aggregate.restoreFindings(findings);
        try {
            String stateJson = objectMapper.writeValueAsString(
                    new StoredReviewState(aggregate.findingsSnapshot()));
            String payloadJson = objectMapper.writeValueAsString(
                    Map.of("body", aggregate.buildReviewBody()));
            return reviewAgentStore.markPublicationReady(task.getId(), stateJson, payloadJson);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("汇总结果无法序列化: " + task.getId(), error);
        }
    }

}

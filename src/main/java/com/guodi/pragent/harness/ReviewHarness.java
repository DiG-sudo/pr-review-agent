package com.guodi.pragent.harness;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.stereotype.Component;


import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.guodi.pragent.persistence.reviewrun.StoredReviewState;
import com.guodi.pragent.preparation.ReviewInitialMessagesBuilder;
import com.guodi.pragent.preparation.GitHubWorkspacePreparer;
import com.guodi.pragent.reviewer.GitHubReviewLookup;
import com.guodi.pragent.reviewer.GitHubReviewPublisher;
import com.guodi.pragent.reviewer.ReviewRequest;
import com.guodi.pragent.reviewer.ReviewState;
import com.guodi.pragent.reviewer.tool.ReviewToolContext;
import com.guodi.pragent.persistence.reviewrun.ReviewRunEntity;
import com.guodi.pragent.persistence.reviewrun.ReviewRunMapper;
import com.guodi.pragent.preparation.GitHubWorkspacePreparer.ReviewWorkspace;
import com.guodi.pragent.runtime.ReviewExecution;
import com.guodi.pragent.runtime.ReviewRunResult;
import com.guodi.pragent.runtime.ReviewStatus;
import com.guodi.pragent.runtime.tool.ToolOutcome;
import com.guodi.pragent.runtime.tool.ToolRegistry;

/** 任务生命周期、推理准备、模型调用和工具轮的插入点。 */
@Component
public class ReviewHarness {

    private final ReviewRunMapper reviewRunMapper;
    private final ToolRoundCoordinator toolRoundCoordinator;
    private final ReviewContextBuilder reviewContextBuilder;
    private final ToolRegistry toolRegistry;
    private final ObjectMapper objectMapper;
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
            ReviewRunRestorer reviewRunRestorer, GitHubWorkspacePreparer workspacePreparer,
            GitHubReviewLookup reviewLookup, GitHubReviewPublisher reviewPublisher) {
        this.reviewRunMapper = reviewRunMapper;
        this.toolRoundCoordinator = toolRoundCoordinator;
        this.reviewContextBuilder = reviewContextBuilder;
        this.toolRegistry = toolRegistry;
        this.objectMapper = objectMapper;
        this.reviewRunRestorer = reviewRunRestorer;
        this.workspacePreparer = workspacePreparer;
        this.reviewLookup = reviewLookup;
        this.reviewPublisher = reviewPublisher;
    }

    /** next 是 Runtime.runLoop；正常返回必须表示已有终态或后续调度已可靠落库。 */
    public ReviewRunResult aroundRun(Long taskId, Function<ReviewExecution, ReviewRunResult> next) {
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
                return new ReviewRunResult(status, null);
            case PENDING:
                throw new IllegalStateException("审查任务尚未被认领: " + taskId);
            case RUNNING:
                break;
        }

        ReviewRequest request = new ReviewRequest(task.getThreadId(), task.getRepository(),
                task.getPullRequestNumber(), task.getHeadSha(), task.getBaseSha(), null);
        ReviewRunResult result;
        try (ReviewWorkspace workspace = workspacePreparer.prepareWorkspace(request)) {
            ReviewExecution execution = task.getInitialMessagesJson() == null
                    ? initializeRun(task, workspace)
                    : reviewRunRestorer.restore(task, workspace);
            //middle
            result = next.apply(execution);
        } catch (IOException error) {
            throw new UncheckedIOException("工作区清理失败: " + taskId, error);
        }

        // after：先可靠落库，再向消费者返回可确认的结果；执行异常直接向外抛。
        return switch (result.status()) {
            case PUBLICATION_READY -> publishAndComplete(reviewRunMapper.selectById(taskId));
            case FAILED -> {
                ReviewRunEntity update = new ReviewRunEntity();
                update.setStatus(ReviewStatus.FAILED.name());
                update.setFinalResultJson("{\"reason\":\"Model call budget exhausted\"}");
                int changed = reviewRunMapper.update(update, Wrappers.<ReviewRunEntity>lambdaUpdate()
                        .eq(ReviewRunEntity::getId, taskId)
                        .eq(ReviewRunEntity::getStatus, ReviewStatus.RUNNING.name()));
                if (changed != 1) {
                    // FAILED 也可能来自推理前对数据库终态的读取。
                    ReviewRunEntity current = reviewRunMapper.selectById(taskId);
                    if (current == null || !ReviewStatus.FAILED.name().equals(current.getStatus())) {
                        throw new IllegalStateException("任务失败状态未保存: " + taskId);
                    }
                }
                yield result;
            }
            case PUBLISHED -> result;
            default -> throw new IllegalStateException("审查循环未正常收口: " + result.status());
        };
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
            return new ReviewRunResult(ReviewStatus.PUBLISHED, null);
        } catch (IOException error) {
            throw new UncheckedIOException("发布或核对失败: " + task.getId(), error);
        }
    }

    /** next 是 modelHandler；RUNNING 结果携带模型响应，其他结果停止循环。 */
    public ReviewRunResult aroundReasoning(ReviewExecution execution, Function<Prompt, ChatResponse> next) {
        ReviewRunEntity task = reviewRunMapper.selectById(execution.getRunId());
        if (task == null) {
            throw new IllegalStateException("审查任务不存在: " + execution.getRunId());
        }
        ReviewStatus status = ReviewStatus.valueOf(task.getStatus());
        switch (status) {
            case PUBLICATION_READY, PUBLISHED, FAILED:
                return new ReviewRunResult(status, null);
            case RUNNING:
                break;
            default:
                throw new IllegalStateException("任务状态不允许推理: " + status);
        }
        if(execution.getModelCalls() >= execution.getMaxModelCalls()){
            return new ReviewRunResult(ReviewStatus.FAILED, null);
        }
        List<Message> modelMessages = reviewContextBuilder.buildModelMessages(execution);
        ToolCallingChatOptions options = ToolCallingChatOptions
                                        .builder()
                                        .toolCallbacks(toolRegistry.getCallbacks())
                                        .internalToolExecutionEnabled(false)
                                        .build();
        Prompt prompt = new Prompt(modelMessages,options);
        // 请求前预占次数，避免模型已调用但异常退出时恢复预算归零。
        int modelCalls = execution.getModelCalls() + 1;
        ReviewRunEntity update = new ReviewRunEntity();
        update.setModelCalls(modelCalls);
        int changed = reviewRunMapper.update(update, Wrappers.<ReviewRunEntity>lambdaUpdate()
                .eq(ReviewRunEntity::getId, execution.getRunId())
                .eq(ReviewRunEntity::getStatus, ReviewStatus.RUNNING.name())
                .eq(ReviewRunEntity::getModelCalls, execution.getModelCalls()));
        if (changed != 1) {
            throw new IllegalStateException("模型调用次数未保存: " + execution.getRunId());
        }
        execution.setModelCalls(modelCalls);

        ChatResponse response = next.apply(prompt);
        //after 预留可能的逻辑
        return new ReviewRunResult(ReviewStatus.RUNNING, response);
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
    public List<ToolOutcome> aroundToolRound(ReviewExecution execution,AssistantMessage assistantMessage) {
        return toolRoundCoordinator.executeAndPersist(execution, assistantMessage);
    }

    /** Workspace 由 aroundRun 管理；初始化完成落库后，才能开始模型和工具执行。 */
    private ReviewExecution initializeRun(ReviewRunEntity task, ReviewWorkspace workspace) {
        ReviewRequest request = new ReviewRequest(task.getThreadId(), task.getRepository(),
                task.getPullRequestNumber(), task.getHeadSha(), task.getBaseSha(), null);
        List<Message> initialMessages = initialMessagesBuilder.buildInitialMessages(
                request, workspace.fileDiffs(), maxInitialDiffChars);
        ReviewState state = new ReviewState(task.getThreadId());
        ToolContext toolContext = new ToolContext(Map.of(ReviewToolContext.KEY,
                new ReviewToolContext(workspace.workspaceDirectory(), state)));
        ReviewExecution execution = new ReviewExecution(task.getId(), initialMessages, List.of(),
                toolContext, 0, maxModelCalls, 1);

        ReviewRunEntity update = new ReviewRunEntity();
        try {
            // 初始消息仅有 system/user 文本，使用明确的存储格式，不序列化框架内部属性。
            update.setInitialMessagesJson(objectMapper.writeValueAsString(initialMessages.stream()
                    .map(message -> Map.of("type", message.getMessageType().getValue(), "text", message.getText()))
                    .toList()));
            update.setReviewStateJson(objectMapper.writeValueAsString(
                    new StoredReviewState(state.findingsSnapshot())));
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("初始化记录无法序列化: " + task.getId(), error);
        }
        update.setId(task.getId());
        update.setModelCalls(0);
        update.setMaxModelCalls(maxModelCalls);
        if (reviewRunMapper.updateById(update) != 1) {
            throw new IllegalStateException("初始化记录未保存: " + task.getId());
        }
        return execution;
    }

}

package com.guodi.pragent.harness;

import java.util.List;
import java.util.function.Function;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;


import com.guodi.pragent.persistence.reviewrun.ReviewRunEntity;
import com.guodi.pragent.persistence.reviewrun.ReviewRunMapper;
import com.guodi.pragent.runtime.ReviewExecution;
import com.guodi.pragent.runtime.ReviewRunResult;
import com.guodi.pragent.runtime.ReviewStatus;
import com.guodi.pragent.runtime.tool.ToolOutcome;
import com.guodi.pragent.runtime.tool.ToolRegistry;

/** 任务生命周期、推理准备、模型调用和工具轮的插入点。 */
@Component
public class ReviewHarness {

    private final ReviewRunMapper reviewRunMapper;
    private final ToolRoundCoordinator toolRounds;
    private final ReviewContextBuilder reviewContextBuilder;
    @Autowired 
    private final ToolRegistry toolRegister;
    public ReviewHarness(ReviewRunMapper reviewRunMapper, ToolRoundCoordinator toolRounds,ReviewContextBuilder reviewContextBuilder) {
        this.reviewRunMapper = reviewRunMapper;
        this.toolRounds = toolRounds;
        this.reviewContextBuilder = reviewContextBuilder;
    }

    /** next 是 Runtime.runLoop；正常返回必须表示已有终态或后续调度已可靠落库。 */
    public ReviewRunResult aroundRun(Long taskId, Function<ReviewExecution, ReviewRunResult> next) {


        // TODO: 根据持久化初始化记录准备新任务或恢复任务，构造 ReviewExecution。
        // TODO: next.apply(execution) 执行循环；READY 接管发布，失败按策略落库。
        // TODO: 可重试审查失败与 Outbox 调度同事务提交，才能返回 PENDING。
        // TODO: finally 清理工作区；无法可靠安排后续执行时向消费者抛异常。
        throw new UnsupportedOperationException("审查任务生命周期尚未实现");
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
        if(execution.getModelCalls()+1>execution.getMaxModelCalls()){
            return new ReviewRunResult(ReviewStatus.FAILED, null);
        }
        List<Message> modelMessages = reviewContextBuilder.buildModelMessages(execution);
        ToolCallingChatOptions options = ToolCallingChatOptions
                                        .builder()
                                        .toolCallbacks(toolRegister.getCallbacks())
                                        .internalToolExecutionEnabled(false)
                                        .build();
        Prompt prompt = new Prompt(modelMessages,options);
        execution.setModelCalls(execution.getModelCalls() + 1);

        ChatResponse ChatResponse = next.apply(prompt);
        //after 预留可能的逻辑
        return new ReviewRunResult(ReviewStatus.RUNNING, ChatResponse);
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

    public List<ToolOutcome> aroundToolRound(ReviewExecution execution, AssistantMessage assistantMessage,ToolRoundCoordinator toolRounds) {
        
    }
}

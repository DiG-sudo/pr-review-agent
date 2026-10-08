package com.guodi.pragent.runtime;

import java.util.List;
import java.util.function.Function;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;

import org.springframework.stereotype.Component;

import com.guodi.pragent.harness.ReviewHarness;
import com.guodi.pragent.runtime.tool.ToolOutcome;
import com.guodi.pragent.runtime.tool.ToolRoundResult;

/** 外部执行入口和核心 ReAct 循环；构造时装配 Harness 插入点。 */
@Component
public class ReviewReActRuntime {

    private final Function<Prompt, ChatResponse> modelHandler;
    private final Function<ReviewExecution, ChatResponse> reasonHandler;
    private final Function<Long, ReviewRunResult> runHandler;
    private final ReviewHarness reviewHarness;
    public ReviewReActRuntime(ReviewHarness reviewHarness, ChatModel chatModel) {
        this.modelHandler = wrapModelCall(reviewHarness, chatModel);
        this.reasonHandler = wrapReasonCall(reviewHarness, modelHandler);
        this.runHandler = wrapRunCall(reviewHarness, this::runLoop);
        this.reviewHarness = reviewHarness;

    }

    /** 消费者只传数据库任务 ID；任务准备和恢复由 Harness 负责。 */
    public ReviewRunResult run(Long taskId) {
        return runHandler.apply(taskId);
    }

    private Function<Prompt, ChatResponse> wrapModelCall(ReviewHarness reviewHarness, ChatModel chatModel) {
        return prompt -> reviewHarness.aroundModelCall(prompt, chatModel::call);
    }

    private Function<ReviewExecution, ChatResponse> wrapReasonCall(ReviewHarness reviewHarness, Function<Prompt, ChatResponse> next) {
        return execution -> reviewHarness.aroundReasoning(execution, next);
    }

    private Function<Long, ReviewRunResult> wrapRunCall(ReviewHarness reviewHarness, Function<ReviewExecution, AgentRunResult> next) {
        return taskId -> reviewHarness.aroundRun(taskId, next);
    }

    private AgentRunResult runLoop(ReviewExecution execution) {
        try {
            while (true) {
                ChatResponse response = reasonHandler.apply(execution);
                AssistantMessage assistantMessage = response.getResult().getOutput();
                if (!assistantMessage.hasToolCalls()) {
                    execution.getHistory().add(assistantMessage);
                    execution.getHistory().add(new UserMessage("请继续审查；完成后单独调用 publish_review。"));
                    continue;
                }

                ToolRoundResult round = reviewHarness.aroundToolRound(execution, assistantMessage);
                List<ToolOutcome> outcomes = round.outcomes();
                execution.getHistory().add(assistantMessage);
                execution.getHistory().add(ToolResponseMessage.builder()
                        .responses(outcomes.stream().map(ToolOutcome::getToolResponse).toList()).build());

                if (round.completed()) {
                    return AgentRunResult.succeeded();
                }
            }
        } catch (AgentRunFailedException failed) {
            return AgentRunResult.failed(failed.getMessage());
        }
    }
}

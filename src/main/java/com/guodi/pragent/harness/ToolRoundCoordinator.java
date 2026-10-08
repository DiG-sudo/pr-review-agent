package com.guodi.pragent.harness;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.AssistantMessage.ToolCall;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.stereotype.Component;

import com.guodi.pragent.persistence.toolround.ToolRoundStore;
import com.guodi.pragent.persistence.reviewagent.ReviewAgentEntity;
import com.guodi.pragent.persistence.reviewagent.ReviewAgentMapper;
import com.guodi.pragent.persistence.reviewrun.ReviewRunEntity;
import com.guodi.pragent.persistence.reviewrun.ReviewRunMapper;
import com.guodi.pragent.runtime.ReviewExecution;
import com.guodi.pragent.runtime.ReviewStatus;
import com.guodi.pragent.runtime.tool.ToolOutcome;
import com.guodi.pragent.runtime.tool.ToolRoundResult;
import com.guodi.pragent.runtime.tool.ToolRoundExecutor;

/** 先保存整轮意图；混合发布只拒绝发布调用；最后提交完整结果和快照。 */
@Component
public class ToolRoundCoordinator {
    private final ToolRoundStore toolRoundStore;
    private final ToolRoundExecutor toolRoundExecutor;
    private final ReviewRunMapper reviewRunMapper;
    private final ReviewAgentMapper reviewAgentMapper;

    public ToolRoundCoordinator(ToolRoundStore toolRoundStore, ToolRoundExecutor toolRoundExecutor,
            ReviewRunMapper reviewRunMapper, ReviewAgentMapper reviewAgentMapper) {
        this.toolRoundStore = toolRoundStore;
        this.toolRoundExecutor = toolRoundExecutor;
        this.reviewRunMapper = reviewRunMapper;
        this.reviewAgentMapper = reviewAgentMapper;
    }

    public ToolRoundResult executeAndPersist(ReviewExecution execution, AssistantMessage assistantMessage) {
        // before：确认任务仍为 RUNNING，保存原始调用意图，过滤混合批次中的发布调用。
        List<ToolCall> calls = assistantMessage.getToolCalls();
        if (calls.isEmpty()) {
            throw new IllegalArgumentException("tool round requires tool calls");
        }
        ReviewRunEntity task = reviewRunMapper.selectById(execution.getReviewRunId());
        if (task == null || !ReviewStatus.RUNNING.name().equals(task.getStatus())) {
            throw new IllegalStateException("review run is missing or not RUNNING: " + execution.getReviewRunId());
        }
        ReviewAgentEntity agent = reviewAgentMapper.selectById(execution.getAgentId());
        if (agent == null || !execution.getReviewRunId().equals(agent.getRunId())
                || agent.getSuccess() != null) {
            throw new IllegalStateException("review Agent is missing or already finished: " + execution.getAgentId());
        }
        Long roundId = toolRoundStore.beginRound(
                execution.getAgentId(), execution.getNextToolRoundNumber(), assistantMessage);

        boolean mixedPublication = calls.size() > 1 && calls.stream().anyMatch(call -> "publish_review".equals(call.name()));

        ToolCall[] allowed = calls.stream().filter(call -> !mixedPublication || !"publish_review".equals(call.name())).toArray(ToolCall[]::new);
        // 执行：按串并行规则调度，并按待执行调用顺序返回结果。
        List<ToolOutcome> executed;
        try {
            executed = toolRoundExecutor.executeRound(allowed, execution.getToolContext());
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("tool round interrupted", error);
        } catch (ExecutionException error) {
            throw new IllegalStateException("tool round execution failed", error.getCause());
        }

        // after：按原始顺序补入被拒绝的发布结果，提交完整结果和状态快照。
        List<ToolOutcome> outcomes = new ArrayList<>(calls.size());
        int index = 0;
        for (ToolCall call : calls) {
            if (mixedPublication && "publish_review".equals(call.name())) {
                String message = "publish_review must be requested alone. Check the other tool results before requesting publication.";
                outcomes.add(new ToolOutcome(call, new ToolResponseMessage.ToolResponse(call.id(), call.name(), message), false));
            } else {
                outcomes.add(executed.get(index++));
            }
        }
        boolean completed = toolRoundStore.completeRound(
                execution.getAgentId(), roundId, outcomes, execution.getReviewState());
        execution.setNextToolRoundNumber(execution.getNextToolRoundNumber() + 1);
        return new ToolRoundResult(outcomes, completed);
    }
}

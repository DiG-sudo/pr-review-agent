package com.guodi.pragent.harness;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.AssistantMessage.ToolCall;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.stereotype.Component;

import com.guodi.pragent.persistence.toolround.ToolRoundStore;
import com.guodi.pragent.runtime.ReviewExecution;
import com.guodi.pragent.runtime.tool.ToolOutcome;
import com.guodi.pragent.runtime.tool.ToolRoundExecutor;

/** 先保存整轮意图；混合发布只拒绝发布调用；最后提交完整结果和快照。 */
@Component
public class ToolRoundCoordinator {
    private final ToolRoundStore toolRoundStore;
    private final ToolRoundExecutor toolRoundExecutor;

    public ToolRoundCoordinator(ToolRoundStore toolRoundStore, ToolRoundExecutor toolRoundExecutor) {
        this.toolRoundStore = toolRoundStore;
        this.toolRoundExecutor = toolRoundExecutor;
    }

    public List<ToolOutcome> executeAndPersist(ReviewExecution execution, AssistantMessage assistantMessage) {
        List<ToolCall> calls = assistantMessage.getToolCalls();
        if (calls.isEmpty()) {
            throw new IllegalArgumentException("tool round requires tool calls");
        }
        Long roundId = toolRoundStore.beginRound(execution.getRunId(), execution.getNextToolRoundNumber(), assistantMessage);
        boolean mixedPublication = calls.size() > 1 && calls.stream().anyMatch(call -> "publish_review".equals(call.name()));
        ToolCall[] allowed = calls.stream().filter(call -> !mixedPublication || !"publish_review".equals(call.name())).toArray(ToolCall[]::new);
        List<ToolOutcome> executed;
        try {
            executed = toolRoundExecutor.executeRound(allowed, execution.getToolContext());
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("tool round interrupted", error);
        } catch (ExecutionException error) {
            throw new IllegalStateException("tool round execution failed", error.getCause());
        }

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
        toolRoundStore.completeRound(execution.getRunId(), roundId, outcomes, execution.getReviewState());
        execution.setNextToolRoundNumber(execution.getNextToolRoundNumber() + 1);
        return List.copyOf(outcomes);
    }
}

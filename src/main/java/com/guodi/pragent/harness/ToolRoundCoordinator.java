package com.guodi.pragent.harness;

import java.util.List;
import java.util.concurrent.ExecutionException;

import org.springframework.ai.chat.messages.AssistantMessage.ToolCall;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ToolContext;

import com.guodi.pragent.persistence.toolround.ToolRoundStore;
import com.guodi.pragent.reviewer.tool.ReviewToolContext;
import com.guodi.pragent.runtime.tool.ToolOutcome;
import com.guodi.pragent.runtime.tool.ToolRoundExecutor;

/** 工具轮次控制：先保存调用意图，再执行工具，最后保存结果和 Finding 快照。 */
public class ToolRoundCoordinator {
    private final ToolRoundStore toolRoundStore;
    private final ToolRoundExecutor toolRoundExecutor;

    public ToolRoundCoordinator(ToolRoundStore toolRoundStore, ToolRoundExecutor toolRoundExecutor) {
        this.toolRoundStore = toolRoundStore;
        this.toolRoundExecutor = toolRoundExecutor;
    }
    public List<ToolOutcome> executeAndPersist(Long runId, int roundNumber,
            AssistantMessage assistantMessage, ToolContext toolContext) {
        Long roundId = toolRoundStore.beginRound(runId, roundNumber, assistantMessage);
        ToolCall[] calls = assistantMessage.getToolCalls().toArray(new ToolCall[0]);

        List<ToolOutcome> outcomes;
        try {
            outcomes = toolRoundExecutor.executeRound(calls, toolContext);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("tool round interrupted", e);
        } catch (ExecutionException e) {
            throw new IllegalStateException("tool round execution failed", e.getCause());
        }

        toolRoundStore.completeRound(runId, roundId, outcomes, ReviewToolContext.from(toolContext).reviewState());
        return outcomes;
    }
}

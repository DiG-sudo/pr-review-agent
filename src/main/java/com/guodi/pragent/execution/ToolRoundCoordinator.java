package com.guodi.pragent.execution;

import java.util.List;
import java.util.concurrent.ExecutionException;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.AssistantMessage.ToolCall;
import org.springframework.ai.chat.model.ToolContext;

import com.guodi.pragent.harness.tool.ToolOutcome;
import com.guodi.pragent.harness.tool.ToolRoundExecutor;
import com.guodi.pragent.reviewer.tool.ReviewToolContext;

/**
 * 完成工具执行编排
 * ToolRoundCoordinator
 */
public class ToolRoundCoordinator {
    private final ToolRoundStore toolRoundStore;
    private final ToolRoundExecutor toolRoundExecutor;
    public ToolRoundCoordinator(ToolRoundStore toolRoundStore,ToolRoundExecutor toolRoundExecutor){
        this.toolRoundStore = toolRoundStore;
        this.toolRoundExecutor = toolRoundExecutor;
    }
    public List<ToolOutcome> execute(Long runId,int roundNumber,AssistantMessage assistantMessage,ToolContext toolContext){


        Long roundId = toolRoundStore.begin(runId,roundNumber,assistantMessage);

        List<ToolCall> toolCalls = assistantMessage.getToolCalls();

        ToolCall[] calls = toolCalls.toArray(new ToolCall[0]);

        List<ToolOutcome> outcomes;
        try {
            outcomes = toolRoundExecutor.executeRound(calls, toolContext);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("tool round interrupted", e);
        } catch (ExecutionException e) {
            throw new IllegalStateException("tool round execution failed", e.getCause());
        }

       toolRoundStore.complete(runId,roundId,outcomes,ReviewToolContext.from(toolContext).reviewState());
       return outcomes;
    }
}

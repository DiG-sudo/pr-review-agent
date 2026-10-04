package com.guodi.pragent.harness.tool;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.AssistantMessage.ToolCall;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import com.guodi.pragent.harness.tool.ToolRegister.Kind;

/** Builds ordered execution batches for one round of tool calls. */
@Component
public final class ToolRoundExecutor {

    private final ToolRegister toolRegister;
    private final ToolExecutor toolExecutor;
    private final ExecutorService executorService;

    public ToolRoundExecutor(
            ToolRegister toolRegister,
            ToolExecutor toolExecutor,
            @Qualifier("reviewReadExecutor") ExecutorService executorService) {
        this.toolRegister = Objects.requireNonNull(toolRegister, "toolRegister cannot be null");
        this.toolExecutor = Objects.requireNonNull(toolExecutor, "toolExecutor cannot be null");
        this.executorService = Objects.requireNonNull(executorService, "executorService cannot be null");
    }

    public List<ToolOutcome> executeRound(
            AssistantMessage.ToolCall[] calls,
            ToolContext toolContext) throws InterruptedException, ExecutionException {
        List<List<Integer>> batches = buildBatches(calls);
        List<ToolOutcome> results = new ArrayList<>(calls.length);

        for (List<Integer> batch : batches) {
            if (batch.size() == 1) {
                ToolOutcome outcome = toolExecutor.execute(calls[batch.get(0)], toolContext);
                results.add(outcome);
                ToolBinding binding = toolRegister.findBinding(outcome.getCall().name());
                if (outcome.isSuccess() && binding != null
                        && binding.getKind() == Kind.TERMINAL) {
                    return List.copyOf(results);
                }
                continue;
            }

            List<Future<ToolOutcome>> futures = new ArrayList<>(batch.size());
            for (Integer index : batch) {
                ToolCall toolCall = calls[index];
                futures.add(executorService.submit(
                        () -> toolExecutor.execute(toolCall, toolContext)));
            }
            for (int index = 0; index < batch.size(); index++) {
                results.add(futures.get(index).get());
            }
        }
        return List.copyOf(results);
    }

    /**
     * Groups consecutive READ calls while keeping WRITE, TERMINAL and unknown tools
     * in their own batches. Every integer is the call's original index.
     */
    public List<List<Integer>> buildBatches(AssistantMessage.ToolCall[] calls) {
        Objects.requireNonNull(calls, "calls cannot be null");
        List<List<Integer>> batches = new ArrayList<>();
        List<Integer> readBatch = new ArrayList<>();
        for (int index = 0; index < calls.length; index++) {
            AssistantMessage.ToolCall call = Objects.requireNonNull(
                    calls[index], "tool call cannot be null");
            ToolBinding binding = toolRegister.findBinding(call.name());

            if (binding != null && binding.getKind() == Kind.READ) {
                readBatch.add(index);
                continue;
            }
            addReadBatch(batches, readBatch);
            batches.add(List.of(index));
        }
        addReadBatch(batches, readBatch);

        return List.copyOf(batches);
    }

    private static void addReadBatch(
            List<List<Integer>> batches,
            List<Integer> readBatch) {
        if (readBatch.isEmpty()) {
            return;
        }
        batches.add(List.copyOf(readBatch));
        readBatch.clear();
    }
}

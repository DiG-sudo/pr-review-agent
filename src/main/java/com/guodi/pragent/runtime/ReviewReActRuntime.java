package com.guodi.pragent.runtime;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;

import com.guodi.pragent.harness.ToolRoundCoordinator;
import com.guodi.pragent.runtime.tool.ToolBinding;
import com.guodi.pragent.runtime.tool.ToolOutcome;
import com.guodi.pragent.runtime.tool.ToolRegistry.Kind;
import com.guodi.pragent.runtime.tool.ToolRegistry;

/** The model/tool loop. The caller prepares and later persists the run state. */
public final class ReviewReActRuntime {

    private final ChatModel chatModel;
    private final ToolRoundCoordinator toolRounds;
    private final ToolRegistry tools;

    public ReviewReActRuntime(ChatModel chatModel, ToolRoundCoordinator toolRounds,ToolRegistry tools) {
        this.chatModel = Objects.requireNonNull(chatModel);
        this.toolRounds = Objects.requireNonNull(toolRounds);
        this.tools = Objects.requireNonNull(tools);
    }

    public ReviewRunResult run(ReviewRunRequest request) {
        Objects.requireNonNull(request);
        List<Message> history = new ArrayList<>(request.initialMessages());
        ToolCallingChatOptions options = ToolCallingChatOptions.builder()
                .toolCallbacks(tools.getCallbacks())
                .internalToolExecutionEnabled(false)
                .build();

        for (int step = 1; step <= request.maxSteps(); step++) {
            // Harness beforeReasoning: select context, enforce budget, and select visible tools.
            // Harness beforeModelCall: record the model-call start.
            ChatResponse response = chatModel.call(new Prompt(List.copyOf(history), options));
            // Harness afterModelCall: record response, usage, and latency.
            AssistantMessage assistant = response.getResult().getOutput();
            history.add(assistant);

            if (!assistant.hasToolCalls()) {
                // Harness afterRun: decide how an unpublished text answer is recorded.
                return new ReviewRunResult(ReviewRunResult.Status.MODEL_STOPPED,
                        step, history);
            }

            // Harness beforeActing: check tool visibility and permissions.
            List<ToolOutcome> outcomes = toolRounds.executeAndPersist(request.runId(), step,
                    assistant, request.toolContext());
            // Harness afterActing: record events and any domain-state changes.
            history.add(ToolResponseMessage.builder()
                    .responses(outcomes.stream().map(ToolOutcome::getToolResponse).toList())
                    .build());

            if (outcomes.stream().anyMatch(this::successfulTerminal)) {
                // Harness afterRun: persist the successful terminal state.
                return new ReviewRunResult(ReviewRunResult.Status.TERMINAL_TOOL_SUCCEEDED,
                        step, history);
            }
        }

        // Harness afterRun: persist the max-steps state. On failure/finally: clean up resources.
        return new ReviewRunResult(ReviewRunResult.Status.MAX_STEPS_REACHED,
                request.maxSteps(), history);
    }

    private boolean successfulTerminal(ToolOutcome outcome) {
        ToolBinding binding = tools.findBinding(outcome.getCall().name());
        return outcome.isSuccess() && binding != null && binding.getKind() == Kind.TERMINAL;
    }
}

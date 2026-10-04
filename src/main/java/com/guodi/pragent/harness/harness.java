package com.guodi.pragent.harness;

import java.util.List;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.tool.ToolCallback;

import com.guodi.pragent.core.ReviewRunRequest;
import com.guodi.pragent.core.ReviewRunResult;
import com.guodi.pragent.harness.tool.ToolOutcome;

/**
 * Lifecycle insertion points around {@code ReviewReActRuntime}.
 *
 * <p>This stays as one collaborator rather than becoming a generic middleware chain. The default
 * methods are intentionally no-op so the ReAct skeleton can be completed before each Harness
 * responsibility is implemented.
 */
public class harness implements ReviewHarness {

    /** H1: restore state, acquire the thread lock, start tracing and prepare the workspace. */
    @Override
    public void beforeRun(ReviewRunRequest request) {
        //
    }

    /** H2: enforce budgets and prepare the messages and visible tools for this reasoning step. */
    public void beforeReasoning(
            ReviewRunRequest request, int step, List<Message> messages, ToolCallback[] tools) {}

    /** H3: record the start of one model call. */
    public void beforeModelCall(ReviewRunRequest request, int step) {}

    /** H4: persist the model response, usage and timing information. */
    public void afterModelCall(ReviewRunRequest request, int step, ChatResponse response) {}

    /** H5: validate permissions and persist tool-call intent before side effects begin. */
    public void beforeActing(
            ReviewRunRequest request, int step, AssistantMessage assistantMessage) {}

    /** H6: persist tool results and any resulting Finding state changes. */
    public void afterActing(
            ReviewRunRequest request, int step, List<ToolOutcome> outcomes) {}

    /** H7: persist the successful or controlled terminal state. */
    public void afterRun(ReviewRunRequest request, ReviewRunResult result) {}

    /** H8: persist the failure state. Resource cleanup belongs in {@link #afterFinally}. */
    public void onFailure(ReviewRunRequest request, RuntimeException error) {}

    /** H9: always release the thread lock, workspace and trace resources. */
    public void afterFinally(ReviewRunRequest request) {}
}

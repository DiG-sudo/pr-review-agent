package com.guodi.pragent.runtime.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.concurrent.ExecutorService;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;

import com.guodi.pragent.runtime.tool.ToolRegistry.Kind;

class ToolRoundExecutorTest {

    @Test
    void stopsAfterSuccessfulTerminalTool() throws Exception {
        ToolRegistry register = mock(ToolRegistry.class);
        ToolExecutor executor = mock(ToolExecutor.class);
        ToolRoundExecutor round = new ToolRoundExecutor(register, executor,
                mock(ExecutorService.class));
        ToolContext context = new ToolContext(Map.of());
        AssistantMessage.ToolCall read = call("1", "get_diff");
        AssistantMessage.ToolCall publish = call("2", "publish_review");
        AssistantMessage.ToolCall later = call("3", "read_file");
        when(register.findBinding("get_diff")).thenReturn(binding(Kind.READ));
        when(register.findBinding("publish_review")).thenReturn(binding(Kind.TERMINAL));
        when(register.findBinding("read_file")).thenReturn(binding(Kind.READ));
        when(executor.execute(read, context)).thenReturn(outcome(read));
        when(executor.execute(publish, context)).thenReturn(outcome(publish));

        var results = round.executeRound(new AssistantMessage.ToolCall[] {
                read, publish, later
        }, context);

        assertThat(results).hasSize(2);
        verify(executor, never()).execute(later, context);
    }

    private static AssistantMessage.ToolCall call(String id, String name) {
        return new AssistantMessage.ToolCall(id, "function", name, "{}");
    }

    private static ToolBinding binding(Kind kind) {
        return new ToolBinding(kind, mock(ToolCallback.class));
    }

    private static ToolOutcome outcome(AssistantMessage.ToolCall call) {
        return new ToolOutcome(call,
                new ToolResponseMessage.ToolResponse(call.id(), call.name(), "ok"), true);
    }
}

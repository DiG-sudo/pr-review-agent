package com.guodi.pragent.runtime.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;

import com.guodi.pragent.runtime.tool.ToolRegistry.Kind;

class ToolRoundExecutorTest {

    @Test
    void rejectedSubmissionBecomesOrderedFailureAndOtherCallsContinue() throws Exception {
        ToolRegistry registry = mock(ToolRegistry.class);
        ToolExecutor executor = mock(ToolExecutor.class);
        ExecutorService pool = mock(ExecutorService.class);
        ToolContext context = new ToolContext(Map.of());
        AssistantMessage.ToolCall first = call("1", "read_file");
        AssistantMessage.ToolCall rejected = call("2", "read_file");
        AssistantMessage.ToolCall third = call("3", "read_file");
        AssistantMessage.ToolCall write = call("4", "add_finding");
        when(registry.findBinding("read_file")).thenReturn(binding(Kind.READ));
        when(registry.findBinding("add_finding")).thenReturn(binding(Kind.WRITE));
        when(executor.execute(first, context)).thenReturn(outcome(first));
        when(executor.execute(third, context)).thenReturn(outcome(third));
        when(executor.execute(write, context)).thenReturn(outcome(write));
        AtomicInteger submissions = new AtomicInteger();
        when(pool.submit(org.mockito.ArgumentMatchers.<Callable<ToolOutcome>>any())).thenAnswer(invocation -> {
            if (submissions.incrementAndGet() == 2) {
                throw new RejectedExecutionException("queue full");
            }
            Callable<ToolOutcome> task = invocation.getArgument(0);
            return CompletableFuture.completedFuture(task.call());
        });

        var results = new ToolRoundExecutor(registry, executor, pool).executeRound(
                new AssistantMessage.ToolCall[] {first, rejected, third, write}, context);

        assertThat(results).extracting(result -> result.getCall().id()).containsExactly("1", "2", "3", "4");
        assertThat(results).extracting(ToolOutcome::isSuccess).containsExactly(true, false, true, true);
        assertThat(results.get(1).getToolResponse().id()).isEqualTo("2");
        assertThat(results.get(1).getToolResponse().responseData()).contains("Tool scheduling failed");
        verify(executor, never()).execute(rejected, context);
        verify(executor).execute(third, context);
        verify(executor).execute(write, context);
    }

    @Test
    void completesAllCallsEvenAfterPublicationTool() throws Exception {
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
        when(executor.execute(later, context)).thenReturn(outcome(later));

        var results = round.executeRound(new AssistantMessage.ToolCall[] {
                read, publish, later
        }, context);

        assertThat(results).hasSize(3);
        verify(executor).execute(later, context);
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

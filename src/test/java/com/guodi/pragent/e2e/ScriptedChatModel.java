package com.guodi.pragent.e2e;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.function.Function;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;

/** Deterministic external-model substitute. Never sends a network request. */
final class ScriptedChatModel implements ChatModel {
    static final java.util.concurrent.atomic.AtomicInteger totalPlannerCalls = new java.util.concurrent.atomic.AtomicInteger();
    static final java.util.concurrent.atomic.AtomicInteger totalReviewCalls = new java.util.concurrent.atomic.AtomicInteger();
    java.util.function.BiConsumer<ChatResponse, Throwable> afterCall = (response,error)->{};
    final Queue<Function<Prompt, ChatResponse>> steps = new java.util.concurrent.ConcurrentLinkedQueue<>();
    Function<Prompt, ChatResponse> routed;
    final List<Prompt> prompts = new java.util.concurrent.CopyOnWriteArrayList<>();
    final List<ChatResponse> responses = new java.util.concurrent.CopyOnWriteArrayList<>();

    final java.util.concurrent.atomic.AtomicInteger plannerCalls = new java.util.concurrent.atomic.AtomicInteger();
    final java.util.concurrent.atomic.AtomicInteger reviewCalls = new java.util.concurrent.atomic.AtomicInteger();
    static boolean isPlanning(Prompt prompt) {
        return prompt.getInstructions().getFirst().getText().startsWith("Group changed files");
    }
    java.util.function.Consumer<Prompt> beforeCall = prompt -> {};

    @Override
    public ChatResponse call(Prompt prompt) {
        (isPlanning(prompt) ? plannerCalls : reviewCalls).incrementAndGet();
        (isPlanning(prompt) ? totalPlannerCalls : totalReviewCalls).incrementAndGet();
        prompts.add(prompt);
        beforeCall.accept(prompt);
        if (routed == null && steps.isEmpty()) {
            throw new AssertionError("Unexpected model call " + prompts.size());
        }
        try {
            Thread.sleep(5);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Scripted model interrupted", error);
        }
        try {
            ChatResponse response = routed == null ? steps.remove().apply(prompt) : routed.apply(prompt);
            if (response != null) responses.add(response);
            afterCall.accept(response, null);
            return response;
        } catch (RuntimeException | AssertionError error) {
            afterCall.accept(null, error);
            throw error;
        }
    }

    void reset() {
        plannerCalls.set(0); reviewCalls.set(0);
        steps.clear();
        prompts.clear();
        responses.clear();
        routed = null;
    }
}

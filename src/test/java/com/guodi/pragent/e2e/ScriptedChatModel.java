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
    final Queue<Function<Prompt, ChatResponse>> steps = new java.util.concurrent.ConcurrentLinkedQueue<>();
    Function<Prompt, ChatResponse> routed;
    final List<Prompt> prompts = new java.util.concurrent.CopyOnWriteArrayList<>();
    final List<ChatResponse> responses = new java.util.concurrent.CopyOnWriteArrayList<>();

    java.util.function.Consumer<Prompt> beforeCall = prompt -> {};

    @Override
    public ChatResponse call(Prompt prompt) {
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
        ChatResponse response = routed == null ? steps.remove().apply(prompt) : routed.apply(prompt);
        if (response != null) responses.add(response);
        return response;
    }

    void reset() {
        steps.clear();
        prompts.clear();
        responses.clear();
        routed = null;
    }
}

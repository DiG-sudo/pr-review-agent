package com.guodi.pragent.harness.tool;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;

import com.guodi.pragent.reviewer.tool.FindingWriteTools;
import com.guodi.pragent.reviewer.tool.ReviewReadTools;
import com.guodi.pragent.reviewer.tool.ReviewTerminalTools;

/** Registers the fixed local reviewer tool set. */
@Component
public final class ToolRegister {

    public enum Kind {
        READ,
        WRITE,
        TERMINAL
    }

    private final Map<String, ToolBinding> bindings;

    public ToolRegister(
            FindingWriteTools findingWriteTools,
            ReviewReadTools reviewReadTools,
            ReviewTerminalTools reviewTerminalTools) {
        Map<String, ToolBinding> registered = new LinkedHashMap<>();
        register(Kind.READ, reviewReadTools, registered);
        register(Kind.WRITE, findingWriteTools, registered);
        register(Kind.TERMINAL, reviewTerminalTools, registered);
        this.bindings = Collections.unmodifiableMap(registered);
    }

    public ToolBinding findBinding(String name) {
        return bindings.get(name);
    }

    public ToolCallback[] callbacks() {
        return bindings.values().stream()
                .map(ToolBinding::getToolCallback)
                .toArray(ToolCallback[]::new);
    }

    private static void register(
            Kind kind,
            Object tools,
            Map<String, ToolBinding> bindings) {
        for (ToolCallback tool : ToolCallbacks.from(tools)) {
            ToolBinding toolBinding = new ToolBinding(kind, tool);
            String name = tool.getToolDefinition().name();
            if (bindings.putIfAbsent(name, toolBinding) != null) {
                throw new IllegalStateException("Duplicate tool name: " + name);
            }
        }
    }
}

package com.guodi.pragent.runtime.tool;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;

/** Holds the assembled tool bindings used by the runtime. */
public final class ToolRegistry {

    public enum Kind {
        READ,
        WRITE,
        TERMINAL
    }

    private final Map<String, ToolBinding> bindings;

    public ToolRegistry(List<ToolBinding> tools) {
        Map<String, ToolBinding> registered = new LinkedHashMap<>();
        for (ToolBinding tool : tools) {
            String name = tool.getToolCallback().getToolDefinition().name();
            if (registered.putIfAbsent(name, tool) != null) {
                throw new IllegalStateException("Duplicate tool name: " + name);
            }
        }
        this.bindings = Collections.unmodifiableMap(registered);
    }

    public ToolBinding findBinding(String name) {
        return bindings.get(name);
    }

    public ToolCallback[] getCallbacks() {
        return bindings.values().stream()
                .map(ToolBinding::getToolCallback)
                .toArray(ToolCallback[]::new);
    }

}

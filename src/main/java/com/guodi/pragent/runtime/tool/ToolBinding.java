package com.guodi.pragent.runtime.tool;

import java.util.Objects;

import org.springframework.ai.tool.ToolCallback;

import com.guodi.pragent.runtime.tool.ToolRegistry.Kind;
import lombok.Getter;

/** Associates a tool callback with its execution kind. */
@Getter
public final class ToolBinding {

    private final Kind kind;
    private final ToolCallback toolCallback;

    public ToolBinding(Kind kind, ToolCallback toolCallback) {
        this.kind = Objects.requireNonNull(kind, "kind cannot be null");
        this.toolCallback = Objects.requireNonNull(toolCallback, "callback cannot be null");
    }
}

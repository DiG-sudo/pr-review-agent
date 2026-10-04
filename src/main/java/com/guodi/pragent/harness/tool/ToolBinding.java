package com.guodi.pragent.harness.tool;

import java.util.Objects;

import lombok.Getter;

import org.springframework.ai.tool.ToolCallback;

import com.guodi.pragent.harness.tool.ToolRegister.Kind;

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

package com.guodi.pragent.reviewer.tool;

import java.util.ArrayList;
import java.util.List;

import org.springframework.ai.support.ToolCallbacks;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.guodi.pragent.runtime.tool.ToolBinding;
import com.guodi.pragent.runtime.tool.ToolRegistry.Kind;
import com.guodi.pragent.runtime.tool.ToolRegistry;

/** Assembles the PR-specific tools with their execution kinds. */
@Configuration
public class ReviewToolConfig {

    @Bean
    public ToolRegistry toolRegistry(ReviewReadTools reads, FindingWriteTools findings,
            ReviewTerminalTools terminal) {
        List<ToolBinding> bindings = new ArrayList<>();
        addToolBindings(bindings, Kind.WRITE, findings);
        addToolBindings(bindings, Kind.READ, reads);
        addToolBindings(bindings, Kind.TERMINAL, terminal);
        return new ToolRegistry(bindings);
    }

    private static void addToolBindings(List<ToolBinding> bindings, Kind kind, Object tools) {
        for (var callback : ToolCallbacks.from(tools)) {
            bindings.add(new ToolBinding(kind, callback));
        }
    }
}

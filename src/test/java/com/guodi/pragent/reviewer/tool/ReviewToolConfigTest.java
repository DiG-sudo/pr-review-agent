package com.guodi.pragent.reviewer.tool;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import com.guodi.pragent.runtime.tool.ToolExecutor;
import com.guodi.pragent.runtime.tool.ToolRegistry.Kind;
import com.guodi.pragent.runtime.tool.ToolRegistry;

class ReviewToolConfigTest {

    @Test
    void wiresTheRuntimeRegistryFromReviewerTools() {
        try (var context = new AnnotationConfigApplicationContext(ReviewToolConfig.class,
                ReviewReadTools.class, FindingWriteTools.class, ReviewTerminalTools.class, ToolExecutor.class)) {
            ToolRegistry tools = context.getBean(ToolRegistry.class);
            assertThat(context.getBeansOfType(ToolRegistry.class)).hasSize(1);
            assertThat(context.getBean(ToolExecutor.class)).isNotNull();
            assertThat(tools.getCallbacks()).extracting(tool -> tool.getToolDefinition().name())
                    .containsExactlyInAnyOrder("get_diff", "read_file", "search_code", "list_findings",
                            "add_finding", "update_finding", "publish_review");
            assertThat(tools.findBinding("read_file").getKind()).isEqualTo(Kind.READ);
            assertThat(tools.findBinding("add_finding").getKind()).isEqualTo(Kind.WRITE);
            assertThat(tools.findBinding("publish_review").getKind()).isEqualTo(Kind.TERMINAL);
        }
    }
}

package com.guodi.pragent.reviewer.tool;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.support.ToolCallbacks;

import com.guodi.pragent.reviewer.ReviewState;

class ReviewerFindingToolsTest {

    @Test
    void runsTheLocalReviewerTools(@TempDir Path fixture) throws IOException {
        Files.createDirectories(fixture.resolve("source/src"));
        Files.writeString(fixture.resolve("metadata.json"), "{\"pr_number\":1}");
        Files.writeString(fixture.resolve("diff.patch"), "diff --git a/src/Example.java b/src/Example.java\n+danger();\n");
        Files.writeString(fixture.resolve("source/src/Example.java"), "class Example {\n  void danger() {}\n}\n");
        ReviewState state = new ReviewState("review-1");
        ToolContext context = new ToolContext(Map.of(
                ReviewToolContext.KEY,
                new ReviewToolContext(fixture, state)));
        ReviewReadTools readTools = new ReviewReadTools();
        FindingWriteTools writeTools = new FindingWriteTools();
        ReviewTerminalTools terminalTools = new ReviewTerminalTools();

        assertThat(ToolCallbacks.from(readTools)).hasSize(4);
        assertThat(ToolCallbacks.from(writeTools)).hasSize(2);
        assertThat(ToolCallbacks.from(terminalTools)).hasSize(1);
        assertThat(readTools.getDiff(null, null, context)).contains("pr_number", "+danger();");
        assertThat(readTools.readFile("src/Example.java", 1, 10, context))
                .contains("1: class Example");
        assertThat(readTools.searchCode("danger", null, 10, context))
                .contains("src/Example.java:2");

        String added = writeTools.addFinding(
                "high", "correctness", "src/Example.java", 17, "Null dereference.", null, context);
        String id = added.substring(added.indexOf("id=") + 3, added.indexOf(" file="));

        writeTools.updateFinding(id, "resolved", null, null, null, "verified", context);

        assertThat(readTools.listFindings(context)).contains(id, "src/Example.java:17", "resolved");
        String body = terminalTools.publishReview(context);
        assertThat(body).contains("Findings (1 total)", "Null dereference.");
        assertThat(terminalTools.publishReview(context)).isEqualTo(body);
        assertThat(state.findingsSnapshot()).hasSize(1);
    }
}

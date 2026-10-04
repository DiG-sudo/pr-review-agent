package com.guodi.pragent.reviewer.tool;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/** Tools that mutate findings in the current review. */
@Component
public final class FindingWriteTools {

    @Tool(name = "add_finding", description = "Record one real issue found in the PR diff.")
    public String addFinding(
            @ToolParam(description = "Finding severity") String severity,
            @ToolParam(description = "Finding category") String category,
            @ToolParam(description = "Repository-relative file path") String file,
            @ToolParam(description = "Changed line number in the new file") int startLine,
            @ToolParam(description = "Concrete explanation of the issue") String description,
            @ToolParam(required = false, description = "Optional small code suggestion")
                    String suggestion,
            ToolContext toolContext) {
        return ReviewToolContext.from(toolContext)
                .reviewState()
                .addFinding(severity, category, file, startLine, description, suggestion);
    }

    @Tool(name = "update_finding", description = "Update an existing review finding.")
    public String updateFinding(
            @ToolParam(description = "Finding ID") String id,
            @ToolParam(required = false, description = "New status") String status,
            @ToolParam(required = false, description = "Updated severity") String severity,
            @ToolParam(required = false, description = "Updated description") String description,
            @ToolParam(required = false, description = "Updated suggestion") String suggestion,
            @ToolParam(required = false, description = "Update note") String note,
            ToolContext toolContext) {
        return ReviewToolContext.from(toolContext)
                .reviewState()
                .updateFinding(id, status, severity, description, suggestion, note);
    }
}

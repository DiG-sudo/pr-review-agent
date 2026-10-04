package com.guodi.pragent.reviewer.tool;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

/** Terminal tools for completing the current review. */
@Component
public final class ReviewTerminalTools {

    @Tool(
            name = "publish_review",
            description = "Publish all recorded findings as the terminal local review.")
    public String publishReview(ToolContext toolContext) {
        return ReviewToolContext.from(toolContext).reviewState().publishReview();
    }
}

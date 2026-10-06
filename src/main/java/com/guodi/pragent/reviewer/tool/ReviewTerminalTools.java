package com.guodi.pragent.reviewer.tool;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

/** 发布工具只准备内容，不执行 GitHub 请求或声明已发布。 */
@Component
public final class ReviewTerminalTools {

    @Tool(
            name = "publish_review",
            description = "Prepare the final review content. Request this tool alone; the Harness handles remote publication.")
    public String publishReview(ToolContext toolContext) {
        return ReviewToolContext.from(toolContext).reviewState().buildReviewBody();
    }
}

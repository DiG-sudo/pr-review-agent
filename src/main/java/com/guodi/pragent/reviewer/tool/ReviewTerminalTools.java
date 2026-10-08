package com.guodi.pragent.reviewer.tool;

import java.lang.reflect.Type;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.execution.ToolCallResultConverter;
import org.springframework.stereotype.Component;

/** 发布工具只准备内容，不执行 GitHub 请求或声明已发布。 */
@Component
public final class ReviewTerminalTools {

    @Tool(
            name = "publish_review",
            description = "Finish the current review scope. Request this tool alone; the Harness aggregates scopes and handles remote publication.",
            resultConverter = PlainTextResultConverter.class)
    public String publishReview(ToolContext toolContext) {
        return ReviewToolContext.from(toolContext).reviewState().buildReviewBody();
    }

    /** Publication consumes the tool result as text, without JSON string encoding. */
    public static final class PlainTextResultConverter implements ToolCallResultConverter {
        @Override
        public String convert(Object result, Type returnType) {
            return (String) result;
        }
    }
}

package com.guodi.pragent.reviewer.tool;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

import org.springframework.ai.chat.model.ToolContext;

import com.guodi.pragent.reviewer.state.ReviewState;

/** Per-review data passed to otherwise stateless reviewer tools. */
public record ReviewToolContext(Path fixtureRoot, ReviewState reviewState) {

    public static final String KEY = ReviewToolContext.class.getName();

    public ReviewToolContext {
        fixtureRoot = requireDirectory(fixtureRoot, "fixture root");
        reviewState = Objects.requireNonNull(reviewState, "reviewState cannot be null");
    }

    public static ReviewToolContext from(ToolContext context) {
        if (context == null) {
            throw new IllegalArgumentException("tool context is required");
        }
        Object value = context.getContext().get(KEY);
        if (value instanceof ReviewToolContext reviewContext) {
            return reviewContext;
        }
        throw new IllegalArgumentException("review tool context is missing");
    }

    private static Path requireDirectory(Path path, String label) {
        if (path == null) {
            throw new IllegalArgumentException(label + " is required");
        }
        try {
            Path realPath = path.toRealPath();
            if (!Files.isDirectory(realPath)) {
                throw new IllegalArgumentException(label + " is not a directory: " + path);
            }
            return realPath;
        } catch (IOException error) {
            throw new IllegalArgumentException(label + " does not exist: " + path, error);
        }
    }
}

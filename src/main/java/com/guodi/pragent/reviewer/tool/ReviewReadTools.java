package com.guodi.pragent.reviewer.tool;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/** Read-only tools for inspecting one local PR fixture and its findings. */
@Component
public final class ReviewReadTools {

    private static final int DEFAULT_PAGE_LINES = 200;
    private static final int MAX_PAGE_LINES = 500;
    private static final int DEFAULT_SEARCH_RESULTS = 50;
    private static final int MAX_SEARCH_RESULTS = 200;

    @Tool(name = "get_diff", description = "Read a page of the current local PR diff and its metadata.")
    public String getDiff(
            @ToolParam(required = false, description = "First diff line to return, starting at 1")
                    Integer startLine,
            @ToolParam(required = false, description = "Number of diff lines to return, maximum 500")
                    Integer lineCount,
            ToolContext toolContext) {
        Path fixtureRoot = ReviewToolContext.from(toolContext).fixtureRoot();
        Path metadataPath = requireFile(fixtureRoot.resolve("metadata.json"), "metadata.json");
        Path diffPath = requireFile(fixtureRoot.resolve("diff.patch"), "diff.patch");
        return "PR metadata:\n"
                + readText(metadataPath)
                + "\n\n"
                + page(diffPath, startLine, lineCount, "Diff");
    }

    @Tool(name = "read_file", description = "Read a page of a source file from the local PR workspace.")
    public String readFile(
            @ToolParam(description = "Repository-relative path under the fixture source directory")
                    String path,
            @ToolParam(required = false, description = "First source line to return, starting at 1")
                    Integer startLine,
            @ToolParam(required = false, description = "Number of source lines to return, maximum 500")
                    Integer lineCount,
            ToolContext toolContext) {
        Path sourceDirectory = sourceDirectory(toolContext);
        Path file = resolveSourcePath(sourceDirectory, path);
        if (!Files.isRegularFile(file)) {
            throw new IllegalArgumentException("source file does not exist: " + path);
        }
        return page(file, startLine, lineCount, sourceDirectory.relativize(file).toString());
    }

    @Tool(name = "search_code", description = "Search source files in the local PR workspace without using a shell.")
    public String searchCode(
            @ToolParam(description = "Case-sensitive text to find") String query,
            @ToolParam(required = false, description = "Optional repository-relative file or directory")
                    String path,
            @ToolParam(required = false, description = "Maximum matches to return, maximum 200")
                    Integer maxResults,
            ToolContext toolContext) {
        String needle = requireText(query, "query");
        Path sourceDirectory = sourceDirectory(toolContext);
        Path base = path == null || path.isBlank() ? sourceDirectory : resolveSourcePath(sourceDirectory, path);
        int limit = positiveLimit(maxResults, DEFAULT_SEARCH_RESULTS, MAX_SEARCH_RESULTS, "maxResults");
        StringBuilder output = new StringBuilder();
        int matches = 0;

        try (Stream<Path> paths = Files.walk(base)) {
            var iterator = paths.filter(Files::isRegularFile).iterator();
            while (iterator.hasNext() && matches < limit) {
                Path file = iterator.next();
                try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                    String line;
                    int lineNumber = 0;
                    while ((line = reader.readLine()) != null && matches < limit) {
                        lineNumber++;
                        if (line.contains(needle)) {
                            output.append(sourceDirectory.relativize(file))
                                    .append(":")
                                    .append(lineNumber)
                                    .append(": ")
                                    .append(line.strip())
                                    .append("\n");
                            matches++;
                        }
                    }
                } catch (IOException ignored) {
                    // Binary or unreadable files are not useful review evidence.
                }
            }
        } catch (IOException error) {
            throw new IllegalStateException("failed to search local source", error);
        }
        return matches == 0 ? "No matches found." : output.toString().stripTrailing();
    }

    @Tool(name = "list_findings", description = "List all findings in the current review.")
    public String listFindings(ToolContext toolContext) {
        return ReviewToolContext.from(toolContext).reviewState().listFindings();
    }

    private static String page(Path file, Integer requestedStart, Integer requestedCount, String label) {
        int start = requestedStart == null ? 1 : requestedStart;
        int count = positiveLimit(requestedCount, DEFAULT_PAGE_LINES, MAX_PAGE_LINES, "lineCount");
        if (start <= 0) {
            throw new IllegalArgumentException("startLine must be positive");
        }
        try {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            if (start > lines.size() && !lines.isEmpty()) {
                throw new IllegalArgumentException(
                        "startLine exceeds file length: " + start + " > " + lines.size());
            }
            int from = Math.min(start - 1, lines.size());
            int to = Math.min(from + count, lines.size());
            StringBuilder output = new StringBuilder(label)
                    .append(" lines ")
                    .append(lines.isEmpty() ? 0 : from + 1)
                    .append("-")
                    .append(to)
                    .append(" of ")
                    .append(lines.size())
                    .append(":");
            for (int index = from; index < to; index++) {
                output.append("\n").append(index + 1).append(": ").append(lines.get(index));
            }
            return output.toString();
        } catch (IOException error) {
            throw new IllegalStateException("failed to read " + label, error);
        }
    }

    private static Path sourceDirectory(ToolContext toolContext) {
        Path fixtureRoot = ReviewToolContext.from(toolContext).fixtureRoot();
        return requireDirectory(fixtureRoot.resolve("source"), "fixture source");
    }

    private static Path resolveSourcePath(Path sourceDirectory, String relativePath) {
        String value = requireText(relativePath, "path");
        try {
            Path resolved = sourceDirectory.resolve(value).normalize().toRealPath();
            if (!resolved.startsWith(sourceDirectory)) {
                throw new IllegalArgumentException("path escapes fixture source: " + relativePath);
            }
            return resolved;
        } catch (IOException error) {
            throw new IllegalArgumentException("source path does not exist: " + relativePath, error);
        }
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

    private static Path requireFile(Path path, String label) {
        try {
            Path realPath = path.toRealPath();
            if (!Files.isRegularFile(realPath)) {
                throw new IllegalArgumentException(label + " is not a file: " + path);
            }
            return realPath;
        } catch (IOException error) {
            throw new IllegalArgumentException(label + " does not exist: " + path, error);
        }
    }

    private static String readText(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException error) {
            throw new IllegalStateException("failed to read " + path.getFileName(), error);
        }
    }

    private static int positiveLimit(Integer value, int defaultValue, int maximum, String field) {
        int resolved = value == null ? defaultValue : value;
        if (resolved <= 0 || resolved > maximum) {
            throw new IllegalArgumentException(field + " must be between 1 and " + maximum);
        }
        return resolved;
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " cannot be blank");
        }
        return value.trim();
    }
}

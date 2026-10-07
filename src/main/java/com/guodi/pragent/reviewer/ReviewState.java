package com.guodi.pragent.reviewer;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Mutable domain state owned by one review run. */
public final class ReviewState {

    private final String threadId;
    private final Map<String, Finding> findings = new LinkedHashMap<>();

    public ReviewState(String threadId) {
        this.threadId = requireText(threadId, "threadId");
    }

    public synchronized String addFinding(String severity, String category, String file, int startLine, String description, String suggestion) {
        // TODO: 新增前按文件、位置和问题内容识别重复 Finding，避免同一问题重复记录。
        if (startLine <= 0) {
            throw new IllegalArgumentException("startLine must be positive");
        }
        String id = UUID.randomUUID().toString();
        Finding finding = new Finding(
                id,
                requireText(severity, "severity"),
                requireText(category, "category"),
                requireText(file, "file"),
                startLine,
                requireText(description, "description"),
                blankToNull(suggestion),
                "open",
                null);
        findings.put(id, finding);
        return "Finding added: id=%s file=%s:%d".formatted(id, finding.file(), startLine);
    }

    public synchronized String updateFinding(String id, String status, String severity, String description, String suggestion, String note) {
        // TODO: 更新问题内容时检查是否与其他 Finding 重复，排除当前 id。
        Finding current = findings.get(id);
        if (current == null) {
            throw new IllegalArgumentException("finding not found: " + id);
        }
        Finding updated = new Finding(
                current.id(),
                choose(severity, current.severity()),
                current.category(),
                current.file(),
                current.startLine(),
                choose(description, current.description()),
                suggestion == null ? current.suggestion() : blankToNull(suggestion),
                choose(status, current.status()),
                choose(note, current.note()));
        findings.put(id, updated);
        return "Finding updated: id=%s status=%s".formatted(id, updated.status());
    }

    public synchronized String listFindings() {
        return findings.isEmpty() ? "No findings recorded yet." : formatFindings();
    }

    /** 只生成固定发布内容；任务状态由工具轮提交事务决定。 */
    public synchronized String buildReviewBody() {
        return findings.isEmpty() ? "No issues found." : formatFindings();
    }

    public synchronized void restoreFindings(List<Finding> snapshot) {
        Map<String, Finding> restored = new LinkedHashMap<>();
        for (Finding finding : snapshot) {
            if (restored.putIfAbsent(finding.id(), finding) != null) {
                throw new IllegalArgumentException("duplicate finding in snapshot: " + finding.id());
            }
        }
        findings.clear();
        findings.putAll(restored);
    }

    public String threadId() {
        return threadId;
    }

    public synchronized List<Finding> findingsSnapshot() {
        return List.copyOf(findings.values());
    }

    private String formatFindings() {
        StringBuilder output = new StringBuilder("Findings (")
                .append(findings.size())
                .append(" total):");
        for (Finding finding : findings.values()) {
            output.append("\n- [")
                    .append(finding.severity())
                    .append("] ")
                    .append(finding.file())
                    .append(":")
                    .append(finding.startLine())
                    .append(" (")
                    .append(finding.status())
                    .append(") id=")
                    .append(finding.id())
                    .append("\n  ")
                    .append(finding.description());
        }
        return output.toString();
    }

    private static String choose(String candidate, String fallback) {
        return candidate == null || candidate.isBlank() ? fallback : candidate.trim();
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " cannot be blank");
        }
        return value.trim();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}

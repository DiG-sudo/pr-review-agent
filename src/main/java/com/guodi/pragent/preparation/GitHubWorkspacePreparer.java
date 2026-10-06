package com.guodi.pragent.preparation;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.eclipse.jgit.api.FetchCommand;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.diff.DiffEntry.ChangeType;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.patch.Patch;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.revwalk.filter.RevFilter;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.TagOpt;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.FileSystemUtils;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.databind.JsonNode;
import com.guodi.pragent.reviewer.ReviewRequest;

/** Fetches one pinned PR revision and prepares its source and per-file diff. */
@Component
public final class GitHubWorkspacePreparer {

    private final RestClient github;
    private final String githubToken;
    private final Path workspaceRoot;
    private final String gitBase;

    @Autowired
    public GitHubWorkspacePreparer(RestClient.Builder restClient, @Value("${GITHUB_TOKEN:}") String githubToken, @Value("${PR_REVIEW_WORKSPACE_ROOT:${java.io.tmpdir}}") String workspaceRoot) {
        this(restClient.baseUrl("https://api.github.com").build(), githubToken,
                Path.of(workspaceRoot), "https://github.com/");
    }

    GitHubWorkspacePreparer(RestClient github, String githubToken,Path workspaceRoot, String gitBase) {
        this.github = github;
        this.githubToken = githubToken;
        this.workspaceRoot = workspaceRoot;
        this.gitBase = gitBase;
    }

    /** The returned handle owns the temporary workspace. */
    public ReviewWorkspace prepareWorkspace(ReviewRequest request) {
        validateRequest(request);
        Path workspace = null;
        try {
            JsonNode pr = github.get()
                    .uri("/repos/{repository}/pulls/{number}", request.repository(), request.pullRequestNumber())
                    .headers(headers -> {
                        headers.set("Accept", "application/vnd.github+json");
                        headers.set("X-GitHub-Api-Version", "2022-11-28");
                        if (githubToken != null && !githubToken.isBlank()) {
                            headers.setBearerAuth(githubToken);
                        }
                    })
                    .retrieve().body(JsonNode.class);
            if (pr == null) {
                throw new IllegalStateException("GitHub PR response is empty");
            }
            verifyExpectedSha(request.baseSha(), pr.path("base").path("sha").asText());
            verifyExpectedSha(request.headSha(), pr.path("head").path("sha").asText());
            if (!request.repository().equalsIgnoreCase(pr.path("base").path("repo").path("full_name").asText())) {
                throw new IllegalStateException("PR base repository does not match the accepted task");
            }
            String baseRef = pr.path("base").path("ref").asText();
            if (baseRef.isBlank() || baseRef.startsWith("-") || baseRef.contains(":")) {
                throw new IllegalStateException("invalid PR base ref");
            }

            Files.createDirectories(workspaceRoot);
            workspace = Files.createTempDirectory(workspaceRoot, "pr-review-" + request.pullRequestNumber() + "-");
            Path sourceDirectory = workspace.resolve("source");
            Path diffFile = workspace.resolve("diff.patch");
            try (Git git = Git.init().setDirectory(sourceDirectory.toFile()).call()) {
                FetchCommand fetch = git.fetch()
                        .setRemote(gitBase + request.repository() + ".git")
                        .setRefSpecs(
                                new RefSpec("+refs/heads/" + baseRef + ":refs/remotes/origin/base"),
                                new RefSpec("+refs/pull/" + request.pullRequestNumber()
                                        + "/head:refs/remotes/origin/pr"))
                        .setTagOpt(TagOpt.NO_TAGS)
                        .setTimeout(120);
                if (githubToken != null && !githubToken.isBlank()) {
                    fetch.setCredentialsProvider(new UsernamePasswordCredentialsProvider("x-access-token", githubToken));
                }
                fetch.call();

                Repository repository = git.getRepository();
                ObjectId base = repository.resolve("refs/remotes/origin/base^{commit}");
                ObjectId head = repository.resolve("refs/remotes/origin/pr^{commit}");
                verifyExpectedSha(request.baseSha(), base == null ? "" : base.name());
                verifyExpectedSha(request.headSha(), head == null ? "" : head.name());

                try (RevWalk walk = new RevWalk(repository)) {
                    walk.setRevFilter(RevFilter.MERGE_BASE);
                    walk.markStart(walk.parseCommit(base));
                    walk.markStart(walk.parseCommit(head));
                    RevCommit mergeBase = walk.next();
                    if (mergeBase == null) {
                        throw new IllegalStateException("PR base and head have no common ancestor");
                    }
                    try (var output = Files.newOutputStream(diffFile);
                            DiffFormatter formatter = new DiffFormatter(output)) {
                        formatter.setRepository(repository);
                        formatter.format(mergeBase.getTree(), walk.parseCommit(head).getTree());
                    }
                }
                git.checkout().setName(request.headSha()).call();
            }

            return new ReviewWorkspace(workspace, sourceDirectory, diffFile, parseFileDiffs(diffFile));
        } catch (IOException | GitAPIException | RuntimeException error) {
            try {
                if (workspace != null) {
                    FileSystemUtils.deleteRecursively(workspace);
                }
            } catch (IOException cleanupError) {
                error.addSuppressed(cleanupError);
            }
            throw new IllegalStateException("cannot prepare PR workspace", error);
        }
    }

    private static void validateRequest(ReviewRequest request) {
        if (request == null || request.threadId() == null || request.repository() == null
                || request.baseSha() == null || request.headSha() == null
                || !request.repository().matches("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+")
                || request.pullRequestNumber() <= 0
                || !request.threadId().equals("github:" + request.repository() + "#" + request.pullRequestNumber())
                || !request.baseSha().matches("[0-9a-fA-F]{40}")
                || !request.headSha().matches("[0-9a-fA-F]{40}")) {
            throw new IllegalArgumentException("invalid GitHub review request");
        }
    }

    private static void verifyExpectedSha(String expected, String actual) {
        if (!expected.equalsIgnoreCase(actual)) {
            throw new IllegalStateException("PR revision changed since the task was accepted");
        }
    }

    private static List<FileDiff> parseFileDiffs(Path diffFile) throws IOException {
        Patch diff = new Patch();
        try (var input = Files.newInputStream(diffFile)) {
            diff.parse(input);
        }
        if (!diff.getErrors().isEmpty()) {
            throw new IllegalArgumentException("invalid PR diff: " + diff.getErrors().getFirst());
        }
        return diff.getFiles().stream()
                .map(file -> new FileDiff(
                        file.getChangeType() == ChangeType.DELETE ? file.getOldPath() : file.getNewPath(),
                        file.getChangeType(),
                        file.getScriptText(StandardCharsets.UTF_8, StandardCharsets.UTF_8)))
                .toList();
    }

    public record FileDiff(String path, ChangeType changeType, String patch) {}

    public static final class ReviewWorkspace implements AutoCloseable {
        private final Path workspace;
        private final Path sourceDirectory;
        private final Path diffFile;
        private final List<FileDiff> fileDiffs;
        private boolean closed;

        private ReviewWorkspace(Path workspace, Path sourceDirectory, Path diffFile, List<FileDiff> fileDiffs) {
            this.workspace = workspace;
            this.sourceDirectory = sourceDirectory;
            this.diffFile = diffFile;
            this.fileDiffs = fileDiffs;
        }

        public Path workspaceDirectory() { return workspace; }
        public Path sourceDirectory() { return sourceDirectory; }
        public Path diffFile() { return diffFile; }
        public List<FileDiff> fileDiffs() { return fileDiffs; }

        @Override
        public void close() throws IOException {
            if (closed) {
                return;
            }
            closed = true;
            FileSystemUtils.deleteRecursively(workspace);
        }
    }
}

package com.guodi.pragent.preparation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.eclipse.jgit.diff.DiffEntry.ChangeType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.client.RestClient;

import com.guodi.pragent.reviewer.ReviewRequest;
import com.sun.net.httpserver.HttpServer;

class GitHubWorkspacePreparerTest {

    @TempDir Path temp;

    @Test
    void preparesPinnedHeadAndDiffThenCleansWorkspace() throws Exception {
        Path source = temp.resolve("source");
        Files.createDirectories(source);
        git(source, "init", "-q", "-b", "main");
        git(source, "config", "user.name", "Test");
        git(source, "config", "user.email", "test@example.com");
        Files.writeString(source.resolve("example.txt"), "before\n");
        Files.writeString(source.resolve("deleted.txt"), "remove me\n");
        git(source, "add", ".");
        git(source, "commit", "-qm", "base");
        String commonAncestor = git(source, "rev-parse", "HEAD");
        git(source, "branch", "base", commonAncestor);
        Files.writeString(source.resolve("example.txt"), "after\n");
        Files.delete(source.resolve("deleted.txt"));
        git(source, "add", "-A");
        git(source, "commit", "-qm", "head");
        String head = git(source, "rev-parse", "HEAD");
        git(source, "checkout", "-q", "base");
        Files.writeString(source.resolve("base-only.txt"), "target branch change\n");
        git(source, "add", ".");
        git(source, "commit", "-qm", "advance base independently");
        String base = git(source, "rev-parse", "HEAD");

        Path remote = temp.resolve("remotes/octo/demo.git");
        Files.createDirectories(remote.getParent());
        git(temp, "clone", "-q", "--bare", source.toString(), remote.toString());
        git(temp, "--git-dir=" + remote, "update-ref", "refs/pull/1/head", head);

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/repos/octo/demo/pulls/1", exchange -> {
            byte[] body = ("""
                    {"base":{"sha":"%s","ref":"base","repo":{"full_name":"octo/demo"}},
                     "head":{"sha":"%s"}}
                    """.formatted(base, head)).getBytes();
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) {
                output.write(body);
            }
        });
        server.start();
        try {
            GitHubWorkspacePreparer agents = new GitHubWorkspacePreparer(
                    RestClient.builder().baseUrl("http://127.0.0.1:" + server.getAddress().getPort()).build(),
                    "", temp.resolve("work"),
                    temp.resolve("remotes").toUri().toString());
            ReviewRequest request = new ReviewRequest("github:octo/demo#1", "octo/demo", 1, head, base, null);
            Path workspace;
            try (GitHubWorkspacePreparer.ReviewWorkspace prepared = agents.prepareWorkspace(request)) {
                workspace = prepared.workspaceDirectory();
                assertThat(Files.readString(prepared.sourceDirectory().resolve("example.txt"))).isEqualTo("after\n");
                assertThat(prepared.sourceDirectory().resolve("base-only.txt")).doesNotExist();
                assertThat(prepared.diffFile().getParent()).isEqualTo(workspace);
                assertThat(Files.readString(prepared.diffFile()))
                        .contains("-before", "+after").doesNotContain("base-only.txt");
                assertThat(prepared.fileDiffs()).extracting(GitHubWorkspacePreparer.FileDiff::path)
                        .containsExactly("deleted.txt", "example.txt");
                assertThat(prepared.fileDiffs().getFirst().changeType()).isEqualTo(ChangeType.DELETE);
                assertThat(new ReviewInitialMessagesBuilder().buildInitialMessages(request, prepared.fileDiffs(), 10_000)
                        .get(1).getText()).contains("### example.txt", "+after");
            }
            assertThat(workspace).doesNotExist();

            ReviewRequest stale = new ReviewRequest("github:octo/demo#1", "octo/demo", 1, base, base, null);
            assertThatThrownBy(() -> agents.prepareWorkspace(stale))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("cannot prepare PR workspace");
            try (var paths = Files.list(temp.resolve("work"))) {
                assertThat(paths.toList()).isEmpty();
            }
        } finally {
            server.stop(0);
        }
    }

    private static String git(Path directory, String... args) throws IOException, InterruptedException {
        List<String> command = new java.util.ArrayList<>(List.of("git"));
        command.addAll(List.of(args));
        Process process = new ProcessBuilder(command).directory(directory.toFile())
                .redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes());
        if (process.waitFor() != 0) {
            throw new IOException("git command failed: " + output);
        }
        return output.trim();
    }
}

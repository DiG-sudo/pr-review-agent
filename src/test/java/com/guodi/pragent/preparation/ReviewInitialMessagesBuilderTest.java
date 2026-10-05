package com.guodi.pragent.preparation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.eclipse.jgit.diff.DiffEntry.ChangeType;
import org.junit.jupiter.api.Test;

import com.guodi.pragent.preparation.GitHubWorkspacePreparer.FileDiff;
import com.guodi.pragent.reviewer.ReviewRequest;

class ReviewInitialMessagesBuilderTest {

    @Test
    void groupsTheDiffByFileAndOmitsWholePatchesWhenTheLimitIsReached() throws Exception {
        List<FileDiff> files = List.of(
                new FileDiff("A.java", ChangeType.MODIFY, "-before\n+after"),
                new FileDiff("B.java", ChangeType.MODIFY, "-old\n+new"));
        ReviewRequest request = new ReviewRequest("thread-1", "owner/repo", 7,
                "head-sha", "base-sha", null);
        ReviewInitialMessagesBuilder prompt = new ReviewInitialMessagesBuilder();

        String full = prompt.buildInitialMessages(request, files, 10_000).get(1).getText();
        assertThat(full).contains("owner/repo", "base-sha", "head-sha",
                "### A.java", "-before", "+after", "### B.java", "-old", "+new");

        String limited = prompt.buildInitialMessages(request, files, 1).get(1).getText();
        assertThat(limited).contains("A.java", "B.java", "patch omitted from initial context")
                .doesNotContain("-before", "-old");
    }
}

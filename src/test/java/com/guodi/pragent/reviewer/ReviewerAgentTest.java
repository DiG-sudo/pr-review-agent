package com.guodi.pragent.reviewer;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.UserMessage;

import com.guodi.pragent.preparation.GitHubWorkspacePreparer.ReviewWorkspace;
import com.guodi.pragent.preparation.GitHubWorkspacePreparer;
import com.guodi.pragent.preparation.ReviewInitialMessagesBuilder;

class ReviewerAgentTest {

    @Test
    void buildsMessagesFromPreparedDiffAndClosesTheWorkspaceOnBothPaths() throws Exception {
        GitHubWorkspacePreparer preparer = mock(GitHubWorkspacePreparer.class);
        ReviewInitialMessagesBuilder prompt = mock(ReviewInitialMessagesBuilder.class);
        ReviewWorkspace prepared = mock(ReviewWorkspace.class);
        ReviewRequest request = new ReviewRequest("thread", "owner/repo", 1, "head", "base", null);
        when(preparer.prepareWorkspace(request)).thenReturn(prepared);
        when(prepared.fileDiffs()).thenReturn(List.of());
        when(prepared.sourceDirectory()).thenReturn(Path.of("source"));
        when(prompt.buildInitialMessages(eq(request), eq(List.of()), anyInt()))
                .thenReturn(List.of(new UserMessage("review")));

        ReviewerAgent agent = new ReviewerAgent(preparer, prompt);
        agent.call(1L, request);
        verify(prepared).close();

        ReviewWorkspace failed = mock(ReviewWorkspace.class);
        when(preparer.prepareWorkspace(request)).thenReturn(failed);
        when(failed.fileDiffs()).thenReturn(List.of());
        when(prompt.buildInitialMessages(eq(request), eq(List.of()), anyInt()))
                .thenThrow(new IllegalStateException("message preparation failed"));

        assertThatThrownBy(() -> agent.call(1L, request))
                .hasMessage("message preparation failed");
        verify(failed).close();
    }
}

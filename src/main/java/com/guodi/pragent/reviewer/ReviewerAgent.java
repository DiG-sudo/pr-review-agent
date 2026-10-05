package com.guodi.pragent.reviewer;

import java.io.IOException;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.stereotype.Component;

import com.guodi.pragent.preparation.GitHubWorkspacePreparer;
import com.guodi.pragent.preparation.ReviewInitialMessagesBuilder;

/** Prepares one review input; Harness and runtime execution will be connected here. */
@Component
public final class ReviewerAgent {

    private static final Logger log = LoggerFactory.getLogger(ReviewerAgent.class);
    private static final int MAX_DIFF_CHARS = 100_000;

    private final GitHubWorkspacePreparer workspacePreparer;
    private final ReviewInitialMessagesBuilder initialMessagesBuilder;

    public ReviewerAgent(GitHubWorkspacePreparer workspacePreparer,
            ReviewInitialMessagesBuilder initialMessagesBuilder) {
        this.workspacePreparer = workspacePreparer;
        this.initialMessagesBuilder = initialMessagesBuilder;
    }

    public void call(long taskId, ReviewRequest request) {
        //获取diff.patch,以及source代码,
        try (GitHubWorkspacePreparer.ReviewWorkspace workspace = workspacePreparer.prepareWorkspace(request)) {
            //准备初始上下文,
            List<Message> initialMessages = initialMessagesBuilder.buildInitialMessages(
                    request, workspace.fileDiffs(), MAX_DIFF_CHARS);

            log.info("Review input prepared: taskId={}, threadId={}, files={}, messages={}, sourceDirectory={}",
                    taskId, request.threadId(), workspace.fileDiffs().size(),
                    initialMessages.size(), workspace.sourceDirectory());

            // TODO: Pass taskId, request, workspace paths and initialMessages to the Harness/Agent here.
        } catch (IOException error) {
            throw new IllegalStateException("cannot close PR workspace", error);
        }
    }
}

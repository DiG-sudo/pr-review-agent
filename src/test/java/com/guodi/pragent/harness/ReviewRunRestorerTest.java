package com.guodi.pragent.harness;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.guodi.pragent.persistence.reviewagent.ReviewAgentEntity;
import com.guodi.pragent.persistence.reviewrun.ReviewRunEntity;
import com.guodi.pragent.persistence.reviewrun.StoredReviewState;
import com.guodi.pragent.persistence.toolround.StoredAssistantMessage;
import com.guodi.pragent.persistence.toolround.StoredToolResponseMessage;
import com.guodi.pragent.persistence.toolround.ToolRoundEntity;
import com.guodi.pragent.persistence.toolround.ToolRoundMapper;
import com.guodi.pragent.reviewer.Finding;
import com.guodi.pragent.reviewer.tool.ReviewToolContext;
import com.guodi.pragent.runtime.ReviewExecution;
import com.guodi.pragent.preparation.GitHubWorkspacePreparer.ReviewWorkspace;
import com.guodi.pragent.runtime.ReviewStatus;

class ReviewRunRestorerTest {

    @Test
    void returnsExecutionWithInitialMessagesCommittedHistoryAndFindings(@TempDir Path workspace) throws Exception {
        ToolRoundMapper rounds = mock(ToolRoundMapper.class);
        TransactionTemplate transactions = mock(TransactionTemplate.class);
        ObjectMapper json = new ObjectMapper();
        Finding finding = new Finding("finding", "high", "bug", "A.java", 1, "Wrong value", null, "open", null);
        ReviewRunEntity task = new ReviewRunEntity();
        task.setId(7L);
        task.setStatus(ReviewStatus.RUNNING.name());
        task.setThreadId("thread");
        ReviewAgentEntity agent = new ReviewAgentEntity();
        agent.setId(9L);
        agent.setRunId(7L);
        agent.setModelCalls(5);
        agent.setMaxModelCalls(12);
        agent.setInitialMessagesJson(json.writeValueAsString(List.of(
                Map.of("type", "system", "text", "instructions"),
                Map.of("type", "user", "text", "review"))));
        agent.setReviewStateJson(json.writeValueAsString(new StoredReviewState(List.of(finding))));
        ToolRoundEntity completed = new ToolRoundEntity();
        completed.setId(11L);
        completed.setRoundNumber(1);
        completed.setStatus("COMPLETED");
        completed.setAssistantMessageJson(json.writeValueAsString(new StoredAssistantMessage("", List.of(new StoredAssistantMessage.StoredToolCall("read", "function", "read_file", "{}")))));
        completed.setToolResponseJson(json.writeValueAsString(new StoredToolResponseMessage(List.of(new StoredToolResponseMessage.StoredToolResult("read", "read_file", true, "source")))));
        ToolRoundEntity open = new ToolRoundEntity();
        open.setId(12L);
        open.setRoundNumber(2);
        open.setStatus("OPEN");
        when(rounds.selectList(any())).thenReturn(List.of(completed, open));
        when(rounds.update(any(), any())).thenReturn(1);
        doAnswer(call -> {
            Consumer<TransactionStatus> action = call.getArgument(0);
            action.accept(mock(TransactionStatus.class));
            return null;
        }).when(transactions).executeWithoutResult(any());
        ReviewWorkspace prepared = mock(ReviewWorkspace.class);
        when(prepared.workspaceDirectory()).thenReturn(workspace);
        when(prepared.fileDiffs()).thenReturn(List.of());
        ReviewExecution execution = new ReviewRunRestorer(rounds, json, transactions)
                .loadExecution(task, agent, prepared);

        assertThat(execution.getInitialMessages()).hasSize(2);
        assertThat(execution.getInitialMessages().getFirst().getText()).isEqualTo("instructions");
        assertThat(ReviewToolContext.from(execution.getToolContext()).fixtureRoot()).isEqualTo(workspace.toRealPath());
        assertThat(execution.getHistory()).hasSize(2);
        assertThat(((AssistantMessage) execution.getHistory().getFirst()).getToolCalls().getFirst().id()).isEqualTo("read");
        assertThat(execution.getReviewState().findingsSnapshot()).containsExactly(finding);
        assertThat(execution.getNextToolRoundNumber()).isEqualTo(3);
        assertThat(execution.getModelCalls()).isEqualTo(5);
        assertThat(execution.getMaxModelCalls()).isEqualTo(12);
        assertThat(execution.getReviewRunId()).isEqualTo(7L);
        assertThat(execution.getAgentId()).isEqualTo(9L);
        ArgumentCaptor<ToolRoundEntity> update = ArgumentCaptor.forClass(ToolRoundEntity.class);
        verify(rounds).update(update.capture(), any());
        assertThat(update.getValue().getStatus()).isEqualTo("ABANDONED");
    }
}

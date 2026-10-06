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
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.guodi.pragent.persistence.reviewrun.ReviewRunEntity;
import com.guodi.pragent.persistence.reviewrun.ReviewRunMapper;
import com.guodi.pragent.persistence.reviewrun.StoredReviewState;
import com.guodi.pragent.persistence.toolround.StoredAssistantMessage;
import com.guodi.pragent.persistence.toolround.StoredToolResponseMessage;
import com.guodi.pragent.persistence.toolround.ToolRoundEntity;
import com.guodi.pragent.persistence.toolround.ToolRoundMapper;
import com.guodi.pragent.reviewer.Finding;
import com.guodi.pragent.reviewer.ReviewState;
import com.guodi.pragent.reviewer.tool.ReviewToolContext;
import com.guodi.pragent.runtime.ReviewExecution;
import com.guodi.pragent.runtime.ReviewStatus;

class ReviewRunRestorerTest {

    @Test
    void restoresCommittedToolsAndFindingsButAbandonsOpenRoundAndKeepsBudget(@TempDir Path workspace) throws Exception {
        ReviewRunMapper reviews = mock(ReviewRunMapper.class);
        ToolRoundMapper rounds = mock(ToolRoundMapper.class);
        TransactionTemplate transactions = mock(TransactionTemplate.class);
        ObjectMapper json = new ObjectMapper();
        Finding finding = new Finding("finding", "high", "bug", "A.java", 1, "Wrong value", null, "open", null);
        ReviewRunEntity task = new ReviewRunEntity();
        task.setId(7L);
        task.setStatus(ReviewStatus.RUNNING.name());
        task.setInitialMessagesJson("[]");
        task.setReviewStateJson(json.writeValueAsString(new StoredReviewState(List.of(finding))));
        when(reviews.selectById(7L)).thenReturn(task);
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
        ReviewState state = new ReviewState("thread");
        ToolContext context = new ToolContext(Map.of(ReviewToolContext.KEY, new ReviewToolContext(workspace, state)));
        ReviewExecution execution = new ReviewExecution(7L, List.of(new UserMessage("review")), List.of(), context, 5, 10, 1);

        new ReviewRunRestorer(reviews, rounds, json, transactions).restore(execution);

        assertThat(execution.getHistory()).hasSize(2);
        assertThat(((AssistantMessage) execution.getHistory().getFirst()).getToolCalls().getFirst().id()).isEqualTo("read");
        assertThat(state.findingsSnapshot()).containsExactly(finding);
        assertThat(execution.getNextToolRoundNumber()).isEqualTo(3);
        assertThat(execution.getModelCalls()).isEqualTo(5);
        ArgumentCaptor<ToolRoundEntity> update = ArgumentCaptor.forClass(ToolRoundEntity.class);
        verify(rounds).update(update.capture(), any());
        assertThat(update.getValue().getStatus()).isEqualTo("ABANDONED");
    }
}

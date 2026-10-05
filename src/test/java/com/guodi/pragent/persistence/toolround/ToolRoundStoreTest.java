package com.guodi.pragent.persistence.toolround;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.guodi.pragent.persistence.reviewrun.ReviewRunEntity;
import com.guodi.pragent.persistence.reviewrun.ReviewRunMapper;
import com.guodi.pragent.persistence.reviewrun.StoredReviewState;
import com.guodi.pragent.reviewer.ReviewState;
import com.guodi.pragent.runtime.tool.ToolOutcome;

class ToolRoundStoreTest {

    private final ToolRoundMapper toolRoundMapper = mock(ToolRoundMapper.class);
    private final ReviewRunMapper reviewRunMapper = mock(ReviewRunMapper.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ToolRoundStore store =
            new ToolRoundStore(toolRoundMapper, reviewRunMapper, objectMapper);

    @Test
    void completeStoresToolResultAndCurrentFindingSnapshot() throws Exception {
        when(toolRoundMapper.update(any(), any())).thenReturn(1);
        when(reviewRunMapper.update(any(), any())).thenReturn(1);

        ReviewState state = new ReviewState("thread-1");
        String added = state.addFinding(
                "high", "correctness", "src/A.java", 12, "Wrong value", null);
        String findingId = added.substring(added.indexOf("id=") + 3, added.indexOf(" file="));
        AssistantMessage.ToolCall call = new AssistantMessage.ToolCall(
                "call-1", "function", "add_finding", "{}");
        ToolOutcome outcome = new ToolOutcome(
                call,
                new ToolResponseMessage.ToolResponse("call-1", "add_finding", added),
                true);

        store.completeRound(10L, 20L, List.of(outcome), state);

        ArgumentCaptor<ToolRoundEntity> round = ArgumentCaptor.forClass(ToolRoundEntity.class);
        verify(toolRoundMapper).update(round.capture(), any());
        assertThat(round.getValue().getStatus()).isEqualTo("COMPLETED");
        StoredToolResponseMessage response = objectMapper.readValue(
                round.getValue().getToolResponseJson(), StoredToolResponseMessage.class);
        assertThat(response.responses()).hasSize(1);
        assertThat(response.responses().getFirst().callId()).isEqualTo("call-1");
        assertThat(response.responses().getFirst().content()).contains(findingId);

        ArgumentCaptor<ReviewRunEntity> run = ArgumentCaptor.forClass(ReviewRunEntity.class);
        verify(reviewRunMapper).update(run.capture(), any());
        StoredReviewState persistedState = objectMapper.readValue(
                run.getValue().getReviewStateJson(), StoredReviewState.class);
        assertThat(persistedState.findings()).hasSize(1);
        assertThat(persistedState.findings().getFirst().id()).isEqualTo(findingId);
    }

    @Test
    void completeDoesNotWriteFindingSnapshotWhenRoundIsNotOpen() {
        ReviewState state = new ReviewState("thread-1");
        AssistantMessage.ToolCall call = new AssistantMessage.ToolCall(
                "call-1", "function", "read_file", "{}");
        ToolOutcome outcome = new ToolOutcome(
                call,
                new ToolResponseMessage.ToolResponse("call-1", "read_file", "content"),
                true);

        assertThatThrownBy(() -> store.completeRound(10L, 20L, List.of(outcome), state))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not OPEN");
        verifyNoInteractions(reviewRunMapper);
    }
}

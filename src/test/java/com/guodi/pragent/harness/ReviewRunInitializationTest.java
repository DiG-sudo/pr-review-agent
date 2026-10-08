package com.guodi.pragent.harness;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.guodi.pragent.persistence.reviewagent.ReviewAgentEntity;
import com.guodi.pragent.persistence.reviewagent.ReviewAgentMapper;
import com.guodi.pragent.persistence.reviewagent.ReviewAgentStore;
import com.guodi.pragent.persistence.reviewrun.ReviewRunMapper;

class ReviewRunInitializationTest {

    @Test
    void persistsAgentInitialMessagesAndEmptyStateBeforeExecution() throws Exception {
        ReviewAgentMapper agents = mock(ReviewAgentMapper.class);
        when(agents.selectList(any())).thenReturn(List.of());
        ObjectMapper json = new ObjectMapper();
        ReviewAgentStore store = new ReviewAgentStore(agents, mock(ReviewRunMapper.class), json);
        List<Message> messages = List.of(
                new SystemMessage("instructions"),
                new UserMessage("review owner/repo"));

        store.createPlan(7L, List.of(new ReviewAgentStore.AgentPlan(messages, List.of("A.java"))), 20);

        ArgumentCaptor<ReviewAgentEntity> saved = ArgumentCaptor.forClass(ReviewAgentEntity.class);
        verify(agents).insert(saved.capture());
        assertThat(saved.getValue().getRunId()).isEqualTo(7L);
        assertThat(saved.getValue().getAgentIndex()).isZero();
        assertThat(saved.getValue().getSuccess()).isNull();
        var initial = json.readTree(saved.getValue().getInitialMessagesJson());
        assertThat(initial).hasSize(2);
        assertThat(initial.get(0).get("type").asText()).isEqualTo("system");
        assertThat(initial.get(1).get("text").asText()).contains("owner/repo");
        assertThat(json.readTree(saved.getValue().getReviewStateJson())
                .get("findings").isEmpty()).isTrue();
        verify(agents).selectList(any());
    }
}

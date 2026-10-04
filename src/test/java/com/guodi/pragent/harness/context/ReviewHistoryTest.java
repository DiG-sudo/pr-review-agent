package com.guodi.pragent.harness.context;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;

class ReviewHistoryTest {

    private final ReviewHistory history = new ReviewHistory();

    @Test
    void keepsRecentWholeRoundsAndMarksOnlyTheVisibleResultAsTruncated() {
        List<Message> complete = new ArrayList<>();
        for (int number = 1; number <= 12; number++) {
            complete.addAll(round(number, "result-" + number));
        }

        List<Message> visible = history.selectHistory(complete, 10, 6);

        assertThat(visible).hasSize(20);
        assertThat(((AssistantMessage) visible.getFirst()).getToolCalls().getFirst().id())
                .isEqualTo("call-3");
        assertThat(((ToolResponseMessage) visible.getLast()).getResponses().getFirst().responseData())
                .contains("showing first 6 of 9 characters", "last line may be incomplete");
        assertThat(((ToolResponseMessage) complete.getLast()).getResponses().getFirst().responseData())
                .isEqualTo("result-12");
    }

    @Test
    void rejectsUnpairedHistory() {
        assertThatThrownBy(() -> history.selectHistory(round(1, "ok").subList(0, 1), 10, 100))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("complete message pairs");
    }

    private List<Message> round(int number, String content) {
        String id = "call-" + number;
        AssistantMessage assistant = AssistantMessage.builder()
                .content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall(id, "function", "read_file", "{}")))
                .build();
        ToolResponseMessage result = ToolResponseMessage.builder()
                .responses(List.of(new ToolResponseMessage.ToolResponse(id, "read_file", content)))
                .build();
        return List.of(assistant, result);
    }
}

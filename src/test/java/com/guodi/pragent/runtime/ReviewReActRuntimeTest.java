package com.guodi.pragent.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;

import com.guodi.pragent.harness.ReviewHarness;
import com.guodi.pragent.harness.ToolRoundCoordinator;
import com.guodi.pragent.persistence.reviewrun.ReviewRunEntity;
import com.guodi.pragent.persistence.reviewrun.ReviewRunMapper;

class ReviewReActRuntimeTest {

    @Test
    void publishedTaskReturnsThroughHarnessWithoutEnteringModelLoop() {
        ReviewRunMapper reviews = mock(ReviewRunMapper.class);
        ToolRoundCoordinator rounds = mock(ToolRoundCoordinator.class);
        ChatModel model = mock(ChatModel.class);
        ReviewRunEntity task = new ReviewRunEntity();
        task.setStatus(ReviewStatus.PUBLISHED.name());
        when(reviews.selectById(7L)).thenReturn(task);

        ReviewReActRuntime runtime = new ReviewReActRuntime(new ReviewHarness(reviews, rounds), model);
        ReviewRunResult result = runtime.run(7L);

        assertThat(result.status()).isEqualTo(ReviewStatus.PUBLISHED);
        assertThat(result.response()).isNull();
        verifyNoInteractions(model, rounds);
    }
}

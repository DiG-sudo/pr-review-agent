package com.guodi.pragent.runtime;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ToolContext;

import com.guodi.pragent.reviewer.ReviewState;
import com.guodi.pragent.reviewer.tool.ReviewToolContext;

import lombok.Getter;
import lombok.Setter;

/** 每次执行独立创建；持有准备或恢复后的数据，供核心循环和 Harness 使用。 */
@Getter
public final class ReviewExecution {

    private final Long runId;
    private final List<Message> initialMessages;
    /** 完整历史可追加；上下文裁剪只操作发送给模型的副本。 */
    private final List<Message> history;
    private final ToolContext toolContext;
    @Setter
    private int modelCalls;
    private final int maxModelCalls;
    @Setter
    private int nextToolRoundNumber;

    /** 与工具适配器引用同一个状态，不复制 Findings。 */
    public ReviewState getReviewState() {
        return ReviewToolContext.from(toolContext).reviewState();
    }

    public ReviewExecution(Long runId, List<Message> initialMessages, List<Message> history, ToolContext toolContext, int modelCalls, int maxModelCalls, int nextToolRoundNumber) {
        this.runId = Objects.requireNonNull(runId, "runId");
        this.initialMessages = List.copyOf(initialMessages);
        this.history = new ArrayList<>(history);
        this.toolContext = Objects.requireNonNull(toolContext, "toolContext");
        if (this.initialMessages.isEmpty() || modelCalls < 0
                || maxModelCalls <= 0 || nextToolRoundNumber <= 0) {
            throw new IllegalArgumentException("执行上下文需要初始消息和有效的预算、轮编号");
        }
        this.modelCalls = modelCalls;
        this.maxModelCalls = maxModelCalls;
        this.nextToolRoundNumber = nextToolRoundNumber;
    }
}

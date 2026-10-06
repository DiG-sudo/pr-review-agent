package com.guodi.pragent.runtime;

import java.util.Objects;

import org.springframework.ai.chat.model.ChatResponse;

/** RUNNING 携带本轮模型响应；其他状态只传递任务状态，不嵌套结果或复制历史。 */
public record ReviewRunResult(ReviewStatus status, ChatResponse response) {

    public ReviewRunResult {
        Objects.requireNonNull(status, "status");
        if ((status == ReviewStatus.RUNNING) != (response != null)) {
            throw new IllegalArgumentException("只有 RUNNING 结果必须携带模型响应");
        }
    }
}

package com.guodi.pragent.runtime;

/** 一个 Agent 执行的最终结果；未完成的执行通过异常向外传播。 */
public record AgentRunResult(boolean success, String failureReason) {

    public AgentRunResult {
        if (success && failureReason != null) {
            throw new IllegalArgumentException("成功结果不能包含失败原因");
        }
        if (!success && (failureReason == null || failureReason.isBlank())) {
            throw new IllegalArgumentException("失败结果必须包含原因");
        }
    }

    public static AgentRunResult succeeded() {
        return new AgentRunResult(true, null);
    }

    public static AgentRunResult failed(String reason) {
        return new AgentRunResult(false, reason);
    }
}

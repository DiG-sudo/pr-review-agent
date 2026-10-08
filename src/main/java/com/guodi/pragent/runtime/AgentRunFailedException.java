package com.guodi.pragent.runtime;

/** 已确定不能继续执行的 Agent 失败；可恢复基础设施异常不使用此类型。 */
public final class AgentRunFailedException extends RuntimeException {

    public AgentRunFailedException(String message) {
        super(message);
    }
}

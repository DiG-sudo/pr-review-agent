package com.guodi.pragent.runtime.tool;

import java.util.List;

/** 已可靠持久化的工具轮结果，以及该轮是否完成当前 Agent。 */
public record ToolRoundResult(List<ToolOutcome> outcomes, boolean completed) {

    public ToolRoundResult {
        outcomes = List.copyOf(outcomes);
    }
}

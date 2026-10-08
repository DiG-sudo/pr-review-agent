package com.guodi.pragent.persistence.reviewagent;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

/** Persisted execution context and final result for one review Agent. */
@Data
@TableName("review_agent")
public class ReviewAgentEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long runId;

    private Integer agentIndex;

    /** Null while unfinished, true after success, false after a deterministic failure. */
    private Boolean success;

    /** Changed-file paths assigned to this Agent; null only for migrated legacy runs. */
    private String filePathsJson;

    private String initialMessagesJson;

    private String reviewStateJson;

    /** Calls are reserved before invoking the model and survive recovery. */
    private Integer modelCalls;

    /** Fixed when the Agent plan is created; every Agent receives the same limit. */
    private Integer maxModelCalls;
}

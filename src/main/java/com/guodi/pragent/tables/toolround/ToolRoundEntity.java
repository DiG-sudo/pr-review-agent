package com.guodi.pragent.tables.toolround;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

/** Database row for one tool round. */
@Data
@TableName("tool_round")
public class ToolRoundEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long runId;

    private Integer roundNumber;

    private String status;

    private String assistantMessageJson;

    /** Complete tool response before context truncation. */
    private String toolResponseJson;

    private String publicationPayloadJson;
}

package com.guodi.pragent.persistence.outbox;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/** Durable notification for a newly accepted review task. */
@Data
@TableName("outbox_event")
public class OutboxEventEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long runId;

    private String eventType;

    private String status;

    private LocalDateTime sentAt;
}

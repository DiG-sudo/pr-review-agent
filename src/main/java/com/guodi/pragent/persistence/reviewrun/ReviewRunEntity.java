package com.guodi.pragent.persistence.reviewrun;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/** Database row for one PR revision review run. */
@Data
@TableName("review_run")
public class ReviewRunEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String threadId;

    private String headSha;

    private String baseSha;

    private String repository;

    private Integer pullRequestNumber;

    /** PENDING、RUNNING、PUBLICATION_READY、PUBLISHED、FAILED。 */
    private String status;

    /** 已预占的模型调用次数；发起请求前持久化，失败请求也计入预算。 */
    private Integer modelCalls;

    /** 初始化时固定的任务预算，恢复时不重新读取配置。 */
    private Integer maxModelCalls;

    private String initialMessagesJson;

    private String reviewStateJson;

    private String finalResultJson;

    /** 单独 publish_review 成功时确定的发布正文，与工具轮次保存同一份内容。 */
    private String publicationPayloadJson;

    private String publicationKey;

    private String externalReviewId;
}

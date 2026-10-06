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

    private String initialMessagesJson;

    private String reviewStateJson;

    private String finalResultJson;

    private String publicationKey;

    private String externalReviewId;
}

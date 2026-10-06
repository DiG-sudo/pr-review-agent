package com.guodi.pragent.entry.queue;

import static com.guodi.pragent.entry.queue.ReviewOutboxPublisher.GROUP;
import static com.guodi.pragent.entry.queue.ReviewOutboxPublisher.STREAM_KEY;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ThreadPoolExecutor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.guodi.pragent.runtime.ReviewReActRuntime;
import com.guodi.pragent.runtime.ReviewRunResult;
import com.guodi.pragent.runtime.ReviewStatus;
import com.guodi.pragent.persistence.reviewrun.ReviewRunEntity;
import com.guodi.pragent.persistence.reviewrun.ReviewRunMapper;

/** 认领任务后调用 Runtime；终态或可靠安排后续执行后确认消息。 */
@Component
public final class ReviewStreamConsumer {

    private static final Logger log = LoggerFactory.getLogger(ReviewStreamConsumer.class);
    private static final String CONSUMER_NAME = "pr-review-local";

    private final StringRedisTemplate redis;
    private final ReviewRunMapper reviews;
    private final ReviewReActRuntime runtime;
    private final ThreadPoolExecutor reviewWorkerExecutor;

    private boolean groupReady;

    public ReviewStreamConsumer(StringRedisTemplate redis, ReviewRunMapper reviews, ReviewReActRuntime runtime, @Qualifier("reviewWorkerExecutor") ThreadPoolExecutor reviewWorkerExecutor) {
        this.redis = redis;
        this.reviews = reviews;
        this.runtime = runtime;
        this.reviewWorkerExecutor = reviewWorkerExecutor;
    }

    @Scheduled(fixedDelayString = "${pr-review.events.poll-delay-ms:500}")
    public void consumeNewMessages() {
        ensureConsumerGroup();
        int freeQueueSlots = reviewWorkerExecutor.getQueue().remainingCapacity();
        if (freeQueueSlots == 0) {
            return;
        }

        // TODO: Recover pending entries (PEL) and failed reviews in one path.

        List<MapRecord<String, String, String>> records = redis.<String, String>opsForStream().read(
        Consumer.from(GROUP, CONSUMER_NAME),
        StreamReadOptions.empty().count(freeQueueSlots),
        StreamOffset.create(STREAM_KEY, ReadOffset.lastConsumed()));
        if (records == null) {
            return;
        }
        for (MapRecord<String, String, String> record : records) {
            reviewWorkerExecutor.execute(() -> processMessage(record));
        }
    }

    private void ensureConsumerGroup() {
        if (groupReady) {
            return;
        }
        byte[] streamKey = STREAM_KEY.getBytes(StandardCharsets.UTF_8);
        try {
            redis.execute((RedisCallback<String>) connection -> connection.streamCommands()
                    .xGroupCreate(streamKey, GROUP, ReadOffset.from("0-0"), true));
        } catch (DataAccessException error) {
            if (!String.valueOf(error.getMostSpecificCause().getMessage()).contains("BUSYGROUP")) {
                throw error;
            }
        }
        groupReady = true;
        log.info("Review Stream group ready: stream={}, group={}", STREAM_KEY, GROUP);
    }

    private void processMessage(MapRecord<String, String, String> record) {
        try {
            long taskId = Long.parseLong(record.getValue().get("run_id"));

            //两个分支,解决重复投递,乐观锁解决并发任务处理
            ReviewRunEntity task = reviews.selectById(taskId);
            if (task != null && ReviewStatus.PUBLICATION_READY.name().equals(task.getStatus())) {
                // TODO: 发布重试需要独立认领策略，接入统一恢复入口；当前保留 PEL，不能当重复消息 ACK。
                log.warn("Publication-ready task awaits recovery dispatch: taskId={}", taskId);
                return;
            }
            if (task == null || !ReviewStatus.PENDING.name().equals(task.getStatus())) {
                redis.opsForStream().acknowledge(STREAM_KEY, GROUP, record.getId());
                return;
            }
            ReviewRunEntity update = new ReviewRunEntity();
            update.setStatus(ReviewStatus.RUNNING.name());
            int claimed = reviews.update(update, Wrappers.<ReviewRunEntity>lambdaUpdate()
                    .eq(ReviewRunEntity::getId, taskId)
                    .eq(ReviewRunEntity::getStatus, ReviewStatus.PENDING.name()));
            if (claimed != 1) {
                redis.opsForStream().acknowledge(STREAM_KEY, GROUP, record.getId());
                return;
            }

            log.info("Review task starting: taskId={}, threadId={}, headSha={}",
                    taskId, task.getThreadId(), task.getHeadSha());
            // 正常返回代表终态，或后续调度已经可靠落库；未处理异常留在 PEL。
            ReviewRunResult result = runtime.run(taskId);
            if (result == null || result.status() == ReviewStatus.RUNNING) {
                throw new IllegalStateException("Agent 未结束本次执行: " + taskId);
            }
            redis.opsForStream().acknowledge(STREAM_KEY, GROUP, record.getId());
            log.info("Review task acknowledged: taskId={}, status={}", taskId, result.status());
        } catch (RuntimeException error) {
            log.error("Review Stream entry failed and remains pending: entry={}", record.getId(), error);
        }
    }
}

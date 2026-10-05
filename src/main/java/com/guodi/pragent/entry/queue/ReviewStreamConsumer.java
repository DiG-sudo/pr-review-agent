package com.guodi.pragent.entry.queue;

import static com.guodi.pragent.entry.queue.ReviewOutboxPublisher.GROUP;
import static com.guodi.pragent.entry.queue.ReviewOutboxPublisher.STREAM_KEY;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ThreadPoolExecutor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
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
import com.guodi.pragent.persistence.reviewrun.ReviewRunEntity;
import com.guodi.pragent.persistence.reviewrun.ReviewRunMapper;
import com.guodi.pragent.reviewer.ReviewRequest;
import com.guodi.pragent.reviewer.ReviewerAgent;

/** Single-process Stream consumer; acknowledgements follow durable terminal state. */
@Component
public final class ReviewStreamConsumer {

    private static final Logger log = LoggerFactory.getLogger(ReviewStreamConsumer.class);
    private static final String CONSUMER_NAME = "pr-review-local";

    private final StringRedisTemplate redis;
    private final ReviewRunMapper reviews;
    private final ObjectProvider<ReviewerAgent> agents;
    private final ThreadPoolExecutor reviewWorkerExecutor;

    private boolean groupReady;

    public ReviewStreamConsumer(StringRedisTemplate redis, ReviewRunMapper reviews,
            ObjectProvider<ReviewerAgent> agents,
            @Qualifier("reviewWorkerExecutor") ThreadPoolExecutor reviewWorkerExecutor) {
        this.redis = redis;
        this.reviews = reviews;
        this.agents = agents;
        this.reviewWorkerExecutor = reviewWorkerExecutor;
    }

    @Scheduled(fixedDelayString = "${pr-review.events.poll-delay-ms:500}")
    public void consumeNewMessages() {
        ensureConsumerGroup();
        ReviewerAgent agent = agents.getIfAvailable();
        int freeQueueSlots = reviewWorkerExecutor.getQueue().remainingCapacity();
        if (agent == null || freeQueueSlots == 0) {
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
            reviewWorkerExecutor.execute(() -> processMessage(record, agent));
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

    private void processMessage(MapRecord<String, String, String> record, ReviewerAgent agent) {
        try {
            long taskId = Long.parseLong(record.getValue().get("run_id"));

            //两个分支,解决重复投递,乐观锁解决并发任务处理
            ReviewRunEntity task = reviews.selectById(taskId);
            if (task == null || !"PENDING".equals(task.getStatus())) {
                redis.opsForStream().acknowledge(STREAM_KEY, GROUP, record.getId());
                return;
            }
            ReviewRunEntity update = new ReviewRunEntity();
            update.setStatus("RUNNING");
            int claimed = reviews.update(update, Wrappers.<ReviewRunEntity>lambdaUpdate()
                    .eq(ReviewRunEntity::getId, taskId)
                    .eq(ReviewRunEntity::getStatus, "PENDING"));
            if (claimed != 1) {
                redis.opsForStream().acknowledge(STREAM_KEY, GROUP, record.getId());
                return;
            }

            log.info("Review task starting: taskId={}, threadId={}, headSha={}",
                    taskId, task.getThreadId(), task.getHeadSha());
            //进入agent
            //TODO 同一prid,注意latestRun
            agent.call(taskId, new ReviewRequest(task.getThreadId(), task.getRepository(),
                    task.getPullRequestNumber(), task.getHeadSha(), task.getBaseSha(), null));
            //TODO 依据agent执行结果判断是否应该ack
            ReviewRunEntity finished = reviews.selectById(taskId);
            if (finished != null && ("PUBLISHED".equals(finished.getStatus())
                    || "FAILED".equals(finished.getStatus())
                    || "SUPERSEDED".equals(finished.getStatus()))) {
                redis.opsForStream().acknowledge(STREAM_KEY, GROUP, record.getId());
                log.info("Review task acknowledged: taskId={}, status={}",
                        taskId, finished.getStatus());
            } else {
                log.warn("Review task returned without durable terminal state: taskId={}", taskId);
            }
        } catch (RuntimeException error) {
            log.error("Review Stream entry failed and remains pending: entry={}", record.getId(), error);
        }
    }
}

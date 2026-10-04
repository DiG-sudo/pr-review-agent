package com.guodi.pragent.reviewer.Event;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.guodi.pragent.tables.outbox.OutboxEventEntity;
import com.guodi.pragent.tables.outbox.OutboxEventMapper;

/** Moves committed Outbox notifications to a Redis Stream; retries are safe. */
@Component
public final class ReviewOutboxPublisher {

    static final String STREAM_KEY = "pr-review:run:v1";
    static final String GROUP = "pr-review-workers";
    private static final Logger log = LoggerFactory.getLogger(ReviewOutboxPublisher.class);

    private final OutboxEventMapper outbox;
    private final StringRedisTemplate redis;

    public ReviewOutboxPublisher(OutboxEventMapper outbox, StringRedisTemplate redis) {
        this.outbox = outbox;
        this.redis = redis;
    }

    @Scheduled(fixedDelayString = "${pr-review.events.publish-delay-ms:500}")
    public void publishPending() {
        List<OutboxEventEntity> pending = outbox.selectList(
                Wrappers.<OutboxEventEntity>lambdaQuery()
                        .eq(OutboxEventEntity::getStatus, "PENDING")
                        .orderByAsc(OutboxEventEntity::getId)
                        .last("LIMIT 50"));
        for (OutboxEventEntity event : pending) {
            try {
                RecordId entry = redis.opsForStream().add(STREAM_KEY, Map.of("run_id", event.getRunId().toString()));
                if (entry == null) {
                    throw new IllegalStateException("Redis Stream did not return an entry ID");
                }
                OutboxEventEntity update = new OutboxEventEntity();
                update.setStatus("SENT");
                update.setSentAt(LocalDateTime.now());
                outbox.update(update, Wrappers.<OutboxEventEntity>lambdaUpdate()
                        .eq(OutboxEventEntity::getId, event.getId())
                        .eq(OutboxEventEntity::getStatus, "PENDING"));
                log.info("Review Outbox published: eventId={}, taskId={}, streamEntry={}",
                        event.getId(), event.getRunId(), entry);
            } catch (DataAccessException error) {
                // XADD may have succeeded before a database failure. Repeating it is allowed.
                log.warn("Review Outbox delivery deferred: eventId={}", event.getId(), error);
                return;
            }
        }
    }
}

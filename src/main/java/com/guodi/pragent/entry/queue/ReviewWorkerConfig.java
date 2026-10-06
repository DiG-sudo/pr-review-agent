package com.guodi.pragent.entry.queue;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Bounded executor for Redis Stream review consumers. */
@Configuration
@EnableScheduling
public class ReviewWorkerConfig {

    @Bean(name = "reviewWorkerExecutor", destroyMethod = "shutdown")
    public ThreadPoolExecutor reviewWorkerExecutor(@Value("${pr-review.execution.workers:8}") int workers, @Value("${pr-review.execution.queue-capacity:16}") int queueCapacity) {
        if (workers <= 0 || queueCapacity <= 0) {
            throw new IllegalArgumentException("review execution sizes must be positive");
        }
        // workers = reviews running at once; the bounded queue holds messages already
        // taken from Redis while those workers are busy.
        return new ThreadPoolExecutor(
                workers, workers, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(queueCapacity),
                Thread.ofPlatform().name("pr-review-worker-", 0).factory(),
                new ThreadPoolExecutor.AbortPolicy());
    }
}

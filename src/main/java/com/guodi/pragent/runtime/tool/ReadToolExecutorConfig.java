package com.guodi.pragent.runtime.tool;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ReadToolExecutorConfig {

    private static final int READ_THREADS = 4;
    private static final int READ_QUEUE_CAPACITY = 8;

    @Bean(
            name = "reviewReadExecutor",
            destroyMethod = "shutdown")
    public ExecutorService reviewReadExecutor() {
        ThreadFactory threadFactory = Thread.ofPlatform()
                .name("pr-review-read-", 0)
                .factory();

        return new ThreadPoolExecutor(
                READ_THREADS,
                READ_THREADS,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(READ_QUEUE_CAPACITY),
                threadFactory,
                new ThreadPoolExecutor.CallerRunsPolicy());
    }
}
package com.knowsource.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
public class AsyncConfig {

    @Bean(name = "ingestExecutor")
    public ThreadPoolTaskExecutor ingestExecutor(
            @Value("${knowsource.async.ingest.core-pool-size:2}") int corePoolSize,
            @Value("${knowsource.async.ingest.max-pool-size:4}") int maxPoolSize,
            @Value("${knowsource.async.ingest.queue-capacity:100}") int queueCapacity) {
        return taskExecutor("ingest-", corePoolSize, maxPoolSize, queueCapacity);
    }

    @Bean(name = "indexExecutor")
    public ThreadPoolTaskExecutor indexExecutor(
            @Value("${knowsource.async.index.core-pool-size:1}") int corePoolSize,
            @Value("${knowsource.async.index.max-pool-size:2}") int maxPoolSize,
            @Value("${knowsource.async.index.queue-capacity:100}") int queueCapacity) {
        return taskExecutor("index-", corePoolSize, maxPoolSize, queueCapacity);
    }

    @Bean(name = "chatExecutor")
    public ThreadPoolTaskExecutor chatExecutor(
            @Value("${knowsource.async.chat.core-pool-size:4}") int corePoolSize,
            @Value("${knowsource.async.chat.max-pool-size:8}") int maxPoolSize,
            @Value("${knowsource.async.chat.queue-capacity:200}") int queueCapacity) {
        return taskExecutor("chat-", corePoolSize, maxPoolSize, queueCapacity);
    }

    @Bean(name = "traceExecutor")
    public ThreadPoolTaskExecutor traceExecutor(
            @Value("${knowsource.async.trace.core-pool-size:1}") int corePoolSize,
            @Value("${knowsource.async.trace.max-pool-size:2}") int maxPoolSize,
            @Value("${knowsource.async.trace.queue-capacity:200}") int queueCapacity) {
        return taskExecutor("trace-", corePoolSize, maxPoolSize, queueCapacity);
    }

    private static ThreadPoolTaskExecutor taskExecutor(
            String threadNamePrefix, int corePoolSize, int maxPoolSize, int queueCapacity) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix(threadNamePrefix);
        executor.setCorePoolSize(corePoolSize);
        executor.setMaxPoolSize(Math.max(corePoolSize, maxPoolSize));
        executor.setQueueCapacity(queueCapacity);
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(10);
        executor.initialize();
        return executor;
    }
}

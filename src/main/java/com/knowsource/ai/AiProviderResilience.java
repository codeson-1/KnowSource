package com.knowsource.ai;

import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.function.Supplier;

import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class AiProviderResilience {

    private final RateLimiter chatRateLimiter;
    private final Bulkhead chatBulkhead;
    private final RateLimiter embeddingRateLimiter;
    private final Bulkhead embeddingBulkhead;
    private final Retry embeddingRetry;
    private final RateLimiter rerankRateLimiter;
    private final Bulkhead rerankBulkhead;
    private final ObjectProvider<DistributedRateLimiter> distRateLimiterProvider;

    @Autowired
    public AiProviderResilience(
            @Value("${knowsource.ai.resilience.chat.limit-for-period:10}") int chatLimitForPeriod,
            @Value("${knowsource.ai.resilience.chat.limit-refresh-period-seconds:1}") long chatLimitRefreshPeriodSeconds,
            @Value("${knowsource.ai.resilience.chat.permission-timeout-millis:0}") long chatPermissionTimeoutMillis,
            @Value("${knowsource.ai.resilience.chat.bulkhead.max-concurrent-calls:10}") int chatMaxConcurrentCalls,
            @Value("${knowsource.ai.resilience.chat.bulkhead.max-wait-millis:0}") long chatBulkheadMaxWaitMillis,
            @Value("${knowsource.ai.resilience.embedding.limit-for-period:5}") int embeddingLimitForPeriod,
            @Value("${knowsource.ai.resilience.embedding.limit-refresh-period-seconds:1}") long embeddingLimitRefreshPeriodSeconds,
            @Value("${knowsource.ai.resilience.embedding.permission-timeout-millis:0}") long embeddingPermissionTimeoutMillis,
            @Value("${knowsource.ai.resilience.embedding.bulkhead.max-concurrent-calls:5}") int embeddingMaxConcurrentCalls,
            @Value("${knowsource.ai.resilience.embedding.bulkhead.max-wait-millis:0}") long embeddingBulkheadMaxWaitMillis,
            @Value("${knowsource.ai.resilience.embedding.retry.max-attempts:3}") int embeddingRetryMaxAttempts,
            @Value("${knowsource.ai.resilience.embedding.retry.wait-millis:500}") long embeddingRetryWaitMillis,
            @Value("${knowsource.ai.resilience.rerank.limit-for-period:10}") int rerankLimitForPeriod,
            @Value("${knowsource.ai.resilience.rerank.limit-refresh-period-seconds:1}") long rerankLimitRefreshPeriodSeconds,
            @Value("${knowsource.ai.resilience.rerank.permission-timeout-millis:0}") long rerankPermissionTimeoutMillis,
            @Value("${knowsource.ai.resilience.rerank.bulkhead.max-concurrent-calls:10}") int rerankMaxConcurrentCalls,
            @Value("${knowsource.ai.resilience.rerank.bulkhead.max-wait-millis:0}") long rerankBulkheadMaxWaitMillis,
            ObjectProvider<DistributedRateLimiter> distRateLimiterProvider) {
        this.chatRateLimiter = rateLimiter(
                "dashscope-chat", chatLimitForPeriod, chatLimitRefreshPeriodSeconds, chatPermissionTimeoutMillis);
        this.chatBulkhead = bulkhead("dashscope-chat", chatMaxConcurrentCalls, chatBulkheadMaxWaitMillis);
        this.embeddingRateLimiter = rateLimiter(
                "dashscope-embedding", embeddingLimitForPeriod, embeddingLimitRefreshPeriodSeconds,
                embeddingPermissionTimeoutMillis);
        this.embeddingBulkhead = bulkhead("dashscope-embedding", embeddingMaxConcurrentCalls, embeddingBulkheadMaxWaitMillis);
        this.embeddingRetry = retry("dashscope-embedding", embeddingRetryMaxAttempts, embeddingRetryWaitMillis);
        this.rerankRateLimiter = rateLimiter(
                "dashscope-rerank", rerankLimitForPeriod, rerankLimitRefreshPeriodSeconds, rerankPermissionTimeoutMillis);
        this.rerankBulkhead = bulkhead("dashscope-rerank", rerankMaxConcurrentCalls, rerankBulkheadMaxWaitMillis);
        this.distRateLimiterProvider = distRateLimiterProvider;
    }

    /**
     * 测试用兼容构造：不传 DistributedRateLimiter，等价于分布式层缺省（走本地兜底）。
     * 现有 plain-JUnit 单元测试 {@code new AiProviderResilience(10,1,0,10,0,...)} 零改动即可编译。
     */
    public AiProviderResilience(
            int chatLimitForPeriod,
            long chatLimitRefreshPeriodSeconds,
            long chatPermissionTimeoutMillis,
            int chatMaxConcurrentCalls,
            long chatBulkheadMaxWaitMillis,
            int embeddingLimitForPeriod,
            long embeddingLimitRefreshPeriodSeconds,
            long embeddingPermissionTimeoutMillis,
            int embeddingMaxConcurrentCalls,
            long embeddingBulkheadMaxWaitMillis,
            int embeddingRetryMaxAttempts,
            long embeddingRetryWaitMillis,
            int rerankLimitForPeriod,
            long rerankLimitRefreshPeriodSeconds,
            long rerankPermissionTimeoutMillis,
            int rerankMaxConcurrentCalls,
            long rerankBulkheadMaxWaitMillis) {
        this(
                chatLimitForPeriod, chatLimitRefreshPeriodSeconds, chatPermissionTimeoutMillis,
                chatMaxConcurrentCalls, chatBulkheadMaxWaitMillis,
                embeddingLimitForPeriod, embeddingLimitRefreshPeriodSeconds, embeddingPermissionTimeoutMillis,
                embeddingMaxConcurrentCalls, embeddingBulkheadMaxWaitMillis,
                embeddingRetryMaxAttempts, embeddingRetryWaitMillis,
                rerankLimitForPeriod, rerankLimitRefreshPeriodSeconds, rerankPermissionTimeoutMillis,
                rerankMaxConcurrentCalls, rerankBulkheadMaxWaitMillis,
                null);
    }

    public <T> T executeChat(Callable<T> callable) {
        return execute("AI chat call failed.", "chat", () -> callable(callable), chatRateLimiter, chatBulkhead, null);
    }

    public void executeChat(Runnable runnable) {
        executeChat(() -> {
            runnable.run();
            return null;
        });
    }

    public <T> T executeEmbedding(Callable<T> callable) {
        return execute(
                "AI embedding call failed.", "embedding", () -> callable(callable),
                embeddingRateLimiter, embeddingBulkhead, embeddingRetry);
    }

    public <T> T executeRerank(Callable<T> callable) {
        return execute("AI rerank call failed.", "rerank", () -> callable(callable), rerankRateLimiter, rerankBulkhead, null);
    }

    /**
     * 装饰链：分布式限流(Redisson, 若启用) → 本地 Bulkhead → 本地 RateLimiter(兜底) → Retry(仅 embedding) → 业务
     *
     * <p>分布式层抛异常/未启用 → 自动降级到纯本地链路，行为与现状完全一致。
     */
    private <T> T execute(
            String failureMessage,
            String channel,
            Supplier<T> supplier,
            RateLimiter rateLimiter,
            Bulkhead bulkhead,
            Retry retry) {
        // 1. 分布式限流（Redisson）
        if (distRateLimiterProvider != null) {
            DistributedRateLimiter dist = distRateLimiterProvider.getIfAvailable();
            if (dist != null && dist.isActive()) {
                if (!dist.tryAcquire(channel)) {
                    throw new AiProviderException("Distributed rate limit exceeded for " + channel);
                }
            }
        }
        // 2. 本地 Bulkhead + 本地 RateLimiter + Retry（沿用现状）
        Supplier<T> decorated = Bulkhead.decorateSupplier(bulkhead,
                RateLimiter.decorateSupplier(rateLimiter, supplier));
        if (retry != null) {
            decorated = Retry.decorateSupplier(retry, decorated);
        }
        try {
            return decorated.get();
        } catch (RuntimeException ex) {
            throw new AiProviderException(failureMessage, ex);
        }
    }

    private static <T> T callable(Callable<T> callable) {
        try {
            return callable.call();
        } catch (RuntimeException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static RateLimiter rateLimiter(
            String name, int limitForPeriod, long refreshPeriodSeconds, long timeoutMillis) {
        RateLimiterConfig config = RateLimiterConfig.custom()
                .limitForPeriod(Math.max(1, limitForPeriod))
                .limitRefreshPeriod(Duration.ofSeconds(Math.max(1L, refreshPeriodSeconds)))
                .timeoutDuration(Duration.ofMillis(Math.max(0L, timeoutMillis)))
                .build();
        return RateLimiter.of(name, config);
    }

    private static Bulkhead bulkhead(String name, int maxConcurrentCalls, long maxWaitMillis) {
        BulkheadConfig config = BulkheadConfig.custom()
                .maxConcurrentCalls(Math.max(1, maxConcurrentCalls))
                .maxWaitDuration(Duration.ofMillis(Math.max(0L, maxWaitMillis)))
                .build();
        return Bulkhead.of(name, config);
    }

    private static Retry retry(String name, int maxAttempts, long waitMillis) {
        RetryConfig config = RetryConfig.custom()
                .maxAttempts(Math.max(1, maxAttempts))
                .waitDuration(Duration.ofMillis(Math.max(0L, waitMillis)))
                .retryExceptions(RuntimeException.class)
                .build();
        return Retry.of(name, config);
    }
}

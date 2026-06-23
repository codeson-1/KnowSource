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
            @Value("${knowsource.ai.resilience.rerank.bulkhead.max-wait-millis:0}") long rerankBulkheadMaxWaitMillis) {
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
    }

    public <T> T executeChat(Callable<T> callable) {
        return execute("AI chat call failed.", () -> callable(callable), chatRateLimiter, chatBulkhead, null);
    }

    public void executeChat(Runnable runnable) {
        executeChat(() -> {
            runnable.run();
            return null;
        });
    }

    public <T> T executeEmbedding(Callable<T> callable) {
        return execute(
                "AI embedding call failed.", () -> callable(callable), embeddingRateLimiter, embeddingBulkhead,
                embeddingRetry);
    }

    public <T> T executeRerank(Callable<T> callable) {
        return execute("AI rerank call failed.", () -> callable(callable), rerankRateLimiter, rerankBulkhead, null);
    }

    private static <T> T execute(
            String failureMessage,
            Supplier<T> supplier,
            RateLimiter rateLimiter,
            Bulkhead bulkhead,
            Retry retry) {
        Supplier<T> decorated = Bulkhead.decorateSupplier(bulkhead, RateLimiter.decorateSupplier(rateLimiter, supplier));
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

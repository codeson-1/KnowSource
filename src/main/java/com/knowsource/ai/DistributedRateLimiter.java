package com.knowsource.ai;

import java.time.Duration;
import java.util.Map;

import com.knowsource.cache.CacheKeys;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RRateLimiter;
import org.redisson.api.RateIntervalUnit;
import org.redisson.api.RateType;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 基于 Redisson {@link RRateLimiter} 的分布式 AI 限流。
 *
 * <p>Redis 未启用或不可达时，{@link #tryAcquire(String)} 一律返回 {@code true}（放行），
 * 由 {@link AiProviderResilience} 的本地 resilience4j {@code RateLimiter} 兜底。
 * 这样单实例行为与引入 Redis 之前完全等价，多实例时才真正生效。
 */
@Slf4j
@Component
public class DistributedRateLimiter {

    private final ObjectProvider<RedissonClient> redissonProvider;
    private final boolean enabled;
    private final Map<String, RateLimitSpec> specs;

    public DistributedRateLimiter(
            ObjectProvider<RedissonClient> redissonProvider,
            @Value("${knowsource.redis.enabled:true}") boolean enabled,
            @Value("${knowsource.ai.resilience.chat.limit-for-period:10}") long chatLimit,
            @Value("${knowsource.ai.resilience.chat.limit-refresh-period-seconds:1}") long chatPeriod,
            @Value("${knowsource.ai.resilience.embedding.limit-for-period:5}") long embedLimit,
            @Value("${knowsource.ai.resilience.embedding.limit-refresh-period-seconds:1}") long embedPeriod,
            @Value("${knowsource.ai.resilience.rerank.limit-for-period:10}") long rerankLimit,
            @Value("${knowsource.ai.resilience.rerank.limit-refresh-period-seconds:1}") long rerankPeriod) {
        this.redissonProvider = redissonProvider;
        this.enabled = enabled;
        this.specs = Map.of(
                "chat", new RateLimitSpec(chatLimit, Duration.ofSeconds(chatPeriod)),
                "embedding", new RateLimitSpec(embedLimit, Duration.ofSeconds(embedPeriod)),
                "rerank", new RateLimitSpec(rerankLimit, Duration.ofSeconds(rerankPeriod)));
    }

    /**
     * 尝试获取一个令牌。
     *
     * @param channel 限流通道：{@code chat} / {@code embedding} / {@code rerank}
     * @return {@code true} = 获取成功可继续；{@code false} = 被限流（调用方应抛限流异常）。
     *         Redis 不可用/未启用 → 一律返回 {@code true}（放行），由本地 resilience4j 兜底。
     */
    public boolean tryAcquire(String channel) {
        if (!enabled) {
            return true;
        }
        RedissonClient client = redissonProvider.getIfAvailable();
        if (client == null) {
            return true;
        }
        try {
            RateLimitSpec spec = specs.get(channel);
            if (spec == null) {
                return true;
            }
            RRateLimiter limiter = client.getRateLimiter(CacheKeys.RATE_LIMIT_PREFIX + channel);
            // 令牌桶：每个 refresh 周期补充 limit 个令牌。
            // trySetRate 仅在首次设置时生效，已存在配置时为 noop，保证多实例配置一致。
            limiter.trySetRate(
                    RateType.OVERALL,
                    spec.limit(),
                    spec.period().toMillis(),
                    RateIntervalUnit.MILLISECONDS);
            return limiter.tryAcquire(1);
        } catch (Exception ex) {
            log.warn("Distributed rate limiter failed for channel={}, allowing (local fallback): {}",
                    channel, ex.toString());
            return true;
        }
    }

    /** 仅 Redis 可达时返回 true（用于决定是否走分布式路径） */
    public boolean isActive() {
        return enabled && redissonProvider.getIfAvailable() != null;
    }

    private record RateLimitSpec(long limit, Duration period) {
    }
}

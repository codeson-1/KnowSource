package com.knowsource.cache;

import java.time.Duration;
import java.util.Optional;
import java.util.Set;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 集中封装所有 Redis 读写 + 降级。
 * 所有业务代码只调它，绝不直接碰 RedisTemplate。
 * Redis 不可用/未启用时所有调用降级为 noop，不阻塞业务。
 */
@Slf4j
@Component
public class CacheService {

    private final ObjectProvider<RedisTemplate<String, Object>> redisProvider;
    private final boolean enabled;

    public CacheService(
            ObjectProvider<RedisTemplate<String, Object>> redisProvider,
            @Value("${knowsource.redis.enabled:true}") boolean enabled) {
        this.redisProvider = redisProvider;
        this.enabled = enabled;
    }

    /** 读：异常/未命中/未启用 → 返回 Optional.empty()，绝不抛 */
    public <T> Optional<T> get(String key, Class<T> type) {
        if (!enabled) {
            return Optional.empty();
        }
        try {
            RedisTemplate<String, Object> redis = redisProvider.getIfAvailable();
            if (redis == null) {
                return Optional.empty();
            }
            Object value = redis.opsForValue().get(key);
            return value == null ? Optional.empty() : Optional.of(type.cast(value));
        } catch (Exception ex) {
            log.warn("Redis get failed for key={}, falling through to DB: {}", key, ex.toString());
            return Optional.empty();
        }
    }

    /** 写：异常吞掉 + log.warn */
    public <T> void put(String key, T value, Duration ttl) {
        if (!enabled) {
            return;
        }
        try {
            RedisTemplate<String, Object> redis = redisProvider.getIfAvailable();
            if (redis == null) {
                return;
            }
            redis.opsForValue().set(key, value, ttl);
        } catch (Exception ex) {
            log.warn("Redis put failed for key={}: {}", key, ex.toString());
        }
    }

    /** 删：异常吞掉 */
    public void evict(String key) {
        if (!enabled) {
            return;
        }
        try {
            RedisTemplate<String, Object> redis = redisProvider.getIfAvailable();
            if (redis != null) {
                redis.delete(key);
            }
        } catch (Exception ex) {
            log.warn("Redis evict failed for key={}: {}", key, ex.toString());
        }
    }

    /** 批量按 pattern 删（KB 删除时清整个 kb 的成员缓存） */
    public void evictByPattern(String pattern) {
        if (!enabled) {
            return;
        }
        try {
            RedisTemplate<String, Object> redis = redisProvider.getIfAvailable();
            if (redis == null) {
                return;
            }
            Set<String> keys = redis.keys(pattern);
            if (keys != null && !keys.isEmpty()) {
                redis.delete(keys);
            }
        } catch (Exception ex) {
            log.warn("Redis evictByPattern failed for pattern={}: {}", pattern, ex.toString());
        }
    }

    /** 测试用 */
    public void flushDb() {
        try {
            RedisTemplate<String, Object> redis = redisProvider.getIfAvailable();
            if (redis != null) {
                redis.getConnectionFactory().getConnection().flushDb();
            }
        } catch (Exception ignored) {
        }
    }
}

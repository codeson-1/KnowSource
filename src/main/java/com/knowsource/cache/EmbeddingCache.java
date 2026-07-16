package com.knowsource.cache;

import java.time.Duration;
import java.util.Optional;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.ByteArrayCodec;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Embedding 向量缓存（仅 query 路径，文档入库不缓存）。
 *
 * <p>三档可选：
 * <ul>
 *   <li>{@link EmbeddingCacheMode#DISABLED} —— 所有方法 noop</li>
 *   <li>{@link EmbeddingCacheMode#L1_ONLY} —— 仅 Caffeine 本地堆内（单实例首选）</li>
 *   <li>{@link EmbeddingCacheMode#L1_L2} —— Caffeine L1 + Redisson 二进制 L2 双级（多实例水平扩容）</li>
 * </ul>
 *
 * <p>设计要点：
 * <ul>
 *   <li>L2 用 {@link ByteArrayCodec} 二进制 codec + 自实现 float[]↔byte[] 转换，
 *       避免 JSON 序列化膨胀（4KB vs 15-20KB）</li>
 *   <li>所有 Redis 调用 try/catch + log.warn，绝不抛异常（与 {@link CacheService} 一致）</li>
 *   <li>L1 失联重启后 L2 兜底，热数据快速恢复</li>
 *   <li>key 用调用方传入的 queryHash（建议 SHA-256），避免长 query 占用内存</li>
 *   <li>不缓存负值（embedding 失败本就抛异常，无需缓存空）</li>
 * </ul>
 */
@Slf4j
@Component
public class EmbeddingCache {

    private static final String KEY_PREFIX = "embed:query:";

    private final EmbeddingCacheMode mode;
    private final Cache<String, float[]> l1;
    private final ObjectProvider<RedissonClient> redissonProvider;
    private final Duration ttl;

    public EmbeddingCache(
            @Value("${knowsource.cache.embedding.mode:disabled}") EmbeddingCacheMode mode,
            @Value("${knowsource.cache.embedding.ttl-seconds:600}") long ttlSeconds,
            @Value("${knowsource.cache.embedding.max-size:1000}") long maxSize,
            ObjectProvider<RedissonClient> redissonProvider) {
        this.mode = mode;
        this.ttl = Duration.ofSeconds(ttlSeconds);
        this.redissonProvider = redissonProvider;
        this.l1 = (mode == EmbeddingCacheMode.DISABLED)
                ? null
                : Caffeine.newBuilder()
                        .maximumSize(maxSize)
                        .expireAfterWrite(this.ttl)
                        .recordStats()
                        .build();
        log.info("EmbeddingCache initialized: mode={}, ttl={}, maxSize={}", mode, this.ttl, maxSize);
    }

    public boolean isEnabled() {
        return mode != EmbeddingCacheMode.DISABLED;
    }

    public EmbeddingCacheMode getMode() {
        return mode;
    }

    /**
     * 查询缓存。
     * <ul>
     *   <li>L1 命中 → 直接返回</li>
     *   <li>L1 miss + L2 启用 → 查 Redis，命中则回填 L1</li>
     *   <li>全 miss 或异常 → 返回 {@link Optional#empty()}</li>
     * </ul>
     */
    public Optional<float[]> get(String queryHash) {
        if (!isEnabled()) {
            return Optional.empty();
        }
        // L1
        float[] l1Hit = l1.getIfPresent(queryHash);
        if (l1Hit != null) {
            return Optional.of(l1Hit);
        }
        // L2
        if (mode != EmbeddingCacheMode.L1_L2) {
            return Optional.empty();
        }
        try {
            RedissonClient redisson = redissonProvider.getIfAvailable();
            if (redisson == null) {
                return Optional.empty();
            }
            RBucket<byte[]> bucket = redisson.getBucket(KEY_PREFIX + queryHash, ByteArrayCodec.INSTANCE);
            byte[] bytes = bucket.get();
            if (bytes == null) {
                return Optional.empty();
            }
            float[] decoded = toFloats(bytes);
            l1.put(queryHash, decoded);  // 回填 L1
            return Optional.of(decoded);
        } catch (Exception ex) {
            log.warn("EmbeddingCache L2 get failed for key={}: {}", queryHash, ex.toString());
            return Optional.empty();
        }
    }

    /**
     * 写入缓存（embedding 调用成功后调用）。
     * <p>L1 + L2（如启用）都写。失败 swallow + log.warn。
     */
    public void put(String queryHash, float[] embedding) {
        if (!isEnabled()) {
            return;
        }
        l1.put(queryHash, embedding);
        if (mode != EmbeddingCacheMode.L1_L2) {
            return;
        }
        try {
            RedissonClient redisson = redissonProvider.getIfAvailable();
            if (redisson == null) {
                return;
            }
            RBucket<byte[]> bucket = redisson.getBucket(KEY_PREFIX + queryHash, ByteArrayCodec.INSTANCE);
            bucket.set(toBytes(embedding), ttl);
        } catch (Exception ex) {
            log.warn("EmbeddingCache L2 put failed for key={}: {}", queryHash, ex.toString());
        }
    }

    /** 测试用：清空 L1（不主动清 L2，TTL 自然过期） */
    public void invalidateAll() {
        if (l1 != null) {
            l1.invalidateAll();
        }
    }

    /** 测试用：L1 命中率统计 */
    public com.github.benmanes.caffeine.cache.stats.CacheStats stats() {
        return l1 == null ? null : l1.stats();
    }

    /** float[] → byte[]，每 float 4 字节 LE 顺序。比 JSON 节省 75% 体积 */
    static byte[] toBytes(float[] values) {
        byte[] out = new byte[values.length * 4];
        int j = 0;
        for (float v : values) {
            int bits = Float.floatToIntBits(v);
            out[j++] = (byte) bits;
            out[j++] = (byte) (bits >> 8);
            out[j++] = (byte) (bits >> 16);
            out[j++] = (byte) (bits >> 24);
        }
        return out;
    }

    /** byte[] → float[]，与 {@link #toBytes} 配套 */
    static float[] toFloats(byte[] bytes) {
        int len = bytes.length / 4;
        float[] out = new float[len];
        int j = 0;
        for (int i = 0; i < len; i++) {
            int bits = (bytes[j] & 0xFF)
                    | ((bytes[j + 1] & 0xFF) << 8)
                    | ((bytes[j + 2] & 0xFF) << 16)
                    | ((bytes[j + 3] & 0xFF) << 24);
            out[i] = Float.intBitsToFloat(bits);
            j += 4;
        }
        return out;
    }
}

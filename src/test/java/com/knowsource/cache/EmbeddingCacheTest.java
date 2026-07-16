package com.knowsource.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.ByteArrayCodec;
import org.springframework.beans.factory.ObjectProvider;

class EmbeddingCacheTest {

    private static final float[] SAMPLE_VEC = new float[]{0.1f, 0.2f, 0.3f, 0.4f};

    @SuppressWarnings("unchecked")
    private RBucket<byte[]> bucket;
    private RedissonClient redisson;
    private ObjectProvider<RedissonClient> provider;

    @BeforeEach
    void setUp() {
        bucket = mock(RBucket.class);
        redisson = mock(RedissonClient.class);
        provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(redisson);
        // 用 doReturn 绕过 Redisson getBucket 的泛型推断问题（when().thenReturn() 类型推断失败）
        doReturn(bucket).when(redisson).getBucket(anyString(), any(ByteArrayCodec.class));
    }

    // ---------- toBytes / toFloats ----------

    @Test
    void toBytesAndToFloatsRoundTrips() {
        float[] original = new float[]{1.5f, -2.7f, 0.0f, Float.MAX_VALUE, Float.MIN_VALUE};
        byte[] bytes = EmbeddingCache.toBytes(original);
        float[] restored = EmbeddingCache.toFloats(bytes);
        assertThat(restored).containsExactly(original);
    }

    // ---------- DISABLED ----------

    @Test
    void disabledReturnsEmpty() {
        EmbeddingCache cache = new EmbeddingCache(EmbeddingCacheMode.DISABLED, 600, 1000, provider);

        Optional<float[]> result = cache.get("any");

        assertThat(result).isEmpty();
        assertThat(cache.isEnabled()).isFalse();
    }

    @Test
    void disabledPutIsNoop() {
        EmbeddingCache cache = new EmbeddingCache(EmbeddingCacheMode.DISABLED, 600, 1000, provider);

        cache.put("any", SAMPLE_VEC);

        verify(redisson, never()).getBucket(anyString(), any(ByteArrayCodec.class));
    }

    // ---------- L1_ONLY ----------

    @Test
    void l1OnlyReturnsCachedValueWhenPresent() {
        EmbeddingCache cache = new EmbeddingCache(EmbeddingCacheMode.L1_ONLY, 600, 1000, provider);
        cache.put("hash1", SAMPLE_VEC);

        Optional<float[]> result = cache.get("hash1");

        assertThat(result).isPresent();
        assertThat(result.get()).containsExactly(SAMPLE_VEC);
        // L1_ONLY 模式不应触达 Redis
        verify(redisson, never()).getBucket(anyString(), any(ByteArrayCodec.class));
    }

    @Test
    void l1OnlyReturnsEmptyOnMiss() {
        EmbeddingCache cache = new EmbeddingCache(EmbeddingCacheMode.L1_ONLY, 600, 1000, provider);

        Optional<float[]> result = cache.get("missing");

        assertThat(result).isEmpty();
        verify(redisson, never()).getBucket(anyString(), any(ByteArrayCodec.class));
    }

    // ---------- L1_L2 ----------

    @Test
    void l1L2ReturnsL1HitWithoutTouchingRedis() {
        EmbeddingCache cache = new EmbeddingCache(EmbeddingCacheMode.L1_L2, 600, 1000, provider);
        cache.put("hash1", SAMPLE_VEC);  // 先写入 L1+L2

        // 重置 mock，第二次查应该只命中 L1
        org.mockito.Mockito.reset(redisson, bucket);

        Optional<float[]> result = cache.get("hash1");

        assertThat(result).isPresent();
        assertThat(result.get()).containsExactly(SAMPLE_VEC);
        verify(redisson, never()).getBucket(anyString(), any(ByteArrayCodec.class));
    }

    @Test
    void l1L2FallsBackToRedisWhenL1Misses() {
        EmbeddingCache cache = new EmbeddingCache(EmbeddingCacheMode.L1_L2, 600, 1000, provider);
        // 模拟 Redis 命中：返回编码后的 SAMPLE_VEC
        byte[] encoded = EmbeddingCache.toBytes(SAMPLE_VEC);
        when(bucket.get()).thenReturn(encoded);

        Optional<float[]> result = cache.get("hash1");

        assertThat(result).isPresent();
        assertThat(result.get()).containsExactly(SAMPLE_VEC);
        // 验证回填了 L1：第二次查应直接命中 L1，不再触达 Redis
        org.mockito.Mockito.reset(redisson, bucket);
        Optional<float[]> secondHit = cache.get("hash1");
        assertThat(secondHit).isPresent();
        verify(redisson, never()).getBucket(anyString(), any(ByteArrayCodec.class));
    }

    @Test
    void l1L2ReturnsEmptyWhenRedisAlsoMisses() {
        EmbeddingCache cache = new EmbeddingCache(EmbeddingCacheMode.L1_L2, 600, 1000, provider);
        when(bucket.get()).thenReturn(null);

        Optional<float[]> result = cache.get("hash1");

        assertThat(result).isEmpty();
    }

    @Test
    void l1L2SwallowsRedisExceptionOnGet() {
        EmbeddingCache cache = new EmbeddingCache(EmbeddingCacheMode.L1_L2, 600, 1000, provider);
        when(bucket.get()).thenThrow(new RuntimeException("connection refused"));

        Optional<float[]> result = cache.get("hash1");

        assertThat(result).isEmpty();
    }

    @Test
    void l1L2SwallowsRedisExceptionOnPut() {
        EmbeddingCache cache = new EmbeddingCache(EmbeddingCacheMode.L1_L2, 600, 1000, provider);
        doThrow(new RuntimeException("write failed"))
                .when(bucket).set(any(byte[].class), any(Duration.class));

        cache.put("hash1", SAMPLE_VEC);  // 不应抛异常

        // L1 应已写入（Redis 失败不回滚 L1）
        Optional<float[]> l1Hit = cache.get("hash1");
        assertThat(l1Hit).isPresent();
    }

    @Test
    void l1L2ReturnsEmptyWhenRedissonNotAvailable() {
        ObjectProvider<RedissonClient> emptyProvider = mock(ObjectProvider.class);
        when(emptyProvider.getIfAvailable()).thenReturn(null);
        EmbeddingCache cache = new EmbeddingCache(EmbeddingCacheMode.L1_L2, 600, 1000, emptyProvider);

        Optional<float[]> result = cache.get("hash1");

        assertThat(result).isEmpty();
    }

    @Test
    void l1L2PutSkipsRedisWhenRedissonNotAvailable() {
        ObjectProvider<RedissonClient> emptyProvider = mock(ObjectProvider.class);
        when(emptyProvider.getIfAvailable()).thenReturn(null);
        EmbeddingCache cache = new EmbeddingCache(EmbeddingCacheMode.L1_L2, 600, 1000, emptyProvider);

        cache.put("hash1", SAMPLE_VEC);

        // L1 应已写入
        Optional<float[]> l1Hit = cache.get("hash1");
        assertThat(l1Hit).isPresent();
    }

    @Test
    void l1L2PutWritesToBothL1AndL2() {
        EmbeddingCache cache = new EmbeddingCache(EmbeddingCacheMode.L1_L2, 600, 1000, provider);

        cache.put("hash1", SAMPLE_VEC);

        // 验证 L2 写入：捕获 set 调用参数
        ArgumentCaptor<byte[]> bytesCaptor = ArgumentCaptor.forClass(byte[].class);
        verify(bucket).set(bytesCaptor.capture(), eq(Duration.ofSeconds(600)));
        byte[] captured = bytesCaptor.getValue();
        assertThat(EmbeddingCache.toFloats(captured)).containsExactly(SAMPLE_VEC);
    }
}

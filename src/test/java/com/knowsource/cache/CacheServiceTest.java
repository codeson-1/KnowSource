package com.knowsource.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

class CacheServiceTest {

    @SuppressWarnings("unchecked")
    private RedisTemplate<String, Object> redisTemplate;
    private ValueOperations<String, Object> valueOps;
    private CacheService cacheService;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redisTemplate = mock(RedisTemplate.class);
        valueOps = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);

        ObjectProvider<RedisTemplate<String, Object>> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(redisTemplate);
        cacheService = new CacheService(provider, true);
    }

    @Test
    void getReturnsCachedValueWhenPresent() {
        CurrentUserValue user = new CurrentUserValue(1L, "alice", "ADMIN", 3);
        when(valueOps.get("user:alice")).thenReturn(user);

        Optional<CurrentUserValue> result = cacheService.get("user:alice", CurrentUserValue.class);

        assertThat(result).isPresent();
        assertThat(result.get().username()).isEqualTo("alice");
    }

    @Test
    void getReturnsEmptyOnMiss() {
        when(valueOps.get("user:bob")).thenReturn(null);

        Optional<CurrentUserValue> result = cacheService.get("user:bob", CurrentUserValue.class);

        assertThat(result).isEmpty();
    }

    @Test
    void getFallsThroughWhenRedisThrows() {
        when(valueOps.get("user:alice")).thenThrow(new RuntimeException("connection refused"));

        Optional<CurrentUserValue> result = cacheService.get("user:alice", CurrentUserValue.class);

        assertThat(result).isEmpty();
    }

    @Test
    void putStoresValueWithTtl() {
        CurrentUserValue user = new CurrentUserValue(1L, "alice", "ADMIN", 3);

        cacheService.put("user:alice", user, Duration.ofSeconds(30));

        verify(valueOps).set("user:alice", user, Duration.ofSeconds(30));
    }

    @Test
    void putSwallowsRedisException() {
        CurrentUserValue user = new CurrentUserValue(1L, "alice", "ADMIN", 3);
        doThrow(new RuntimeException("write failed"))
                .when(valueOps).set(eq("user:alice"), eq(user), any(Duration.class));

        cacheService.put("user:alice", user, Duration.ofSeconds(30));

        // 不抛异常即可
        verify(valueOps).set(eq("user:alice"), eq(user), any(Duration.class));
    }

    @Test
    void evictDeletesKey() {
        cacheService.evict("user:alice");

        verify(redisTemplate).delete("user:alice");
    }

    @Test
    void evictSwallowsRedisException() {
        when(redisTemplate.delete("user:alice")).thenThrow(new RuntimeException("delete failed"));

        cacheService.evict("user:alice");

        verify(redisTemplate).delete("user:alice");
    }

    @Test
    void evictByPatternDeletesAllMatchingKeys() {
        Set<String> keys = Set.of("kbmember:kb1:1", "kbmember:kb1:2");
        when(redisTemplate.keys("kbmember:kb1:*")).thenReturn(keys);

        cacheService.evictByPattern("kbmember:kb1:*");

        verify(redisTemplate).delete(keys);
    }

    @Test
    void evictByPatternDoesNothingWhenNoKeysMatch() {
        when(redisTemplate.keys("kbmember:kb1:*")).thenReturn(Set.of());

        cacheService.evictByPattern("kbmember:kb1:*");

        verify(redisTemplate, never()).delete(any(Set.class));
    }

    @Test
    void allOperationsAreNoopWhenDisabled() {
        ObjectProvider<RedisTemplate<String, Object>> provider = mock(ObjectProvider.class);
        CacheService disabled = new CacheService(provider, false);

        assertThat(disabled.get("user:alice", CurrentUserValue.class)).isEmpty();

        disabled.put("user:alice", new CurrentUserValue(1L, "alice", "ADMIN", 3), Duration.ofSeconds(30));
        disabled.evict("user:alice");
        disabled.evictByPattern("kbmember:kb1:*");

        verify(provider, never()).getIfAvailable();
    }

    @Test
    void getReturnsEmptyWhenRedisTemplateNotAvailable() {
        ObjectProvider<RedisTemplate<String, Object>> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);
        CacheService noRedis = new CacheService(provider, true);

        Optional<CurrentUserValue> result = noRedis.get("user:alice", CurrentUserValue.class);

        assertThat(result).isEmpty();
    }

    @Test
    void putIsNoopWhenRedisTemplateNotAvailable() {
        ObjectProvider<RedisTemplate<String, Object>> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);
        CacheService noRedis = new CacheService(provider, true);

        noRedis.put("user:alice", new CurrentUserValue(1L, "alice", "ADMIN", 3), Duration.ofSeconds(30));

        verify(valueOps, never()).set(any(), any(), any(Duration.class));
    }

    record CurrentUserValue(long id, String username, String globalRole, int tokenVersion) {
    }
}

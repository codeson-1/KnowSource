package com.knowsource.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.redisson.api.RRateLimiter;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.ObjectProvider;

class DistributedRateLimiterTest {

    @Test
    void tryAcquireReturnsTrueWhenRedisDisabled() {
        ObjectProvider<RedissonClient> provider = mock(ObjectProvider.class);
        DistributedRateLimiter limiter = new DistributedRateLimiter(provider, false, 10, 1, 5, 1, 10, 1);

        assertThat(limiter.tryAcquire("chat")).isTrue();
        assertThat(limiter.tryAcquire("embedding")).isTrue();
        assertThat(limiter.tryAcquire("rerank")).isTrue();
    }

    @Test
    void tryAcquireReturnsTrueWhenRedissonClientNotAvailable() {
        ObjectProvider<RedissonClient> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);
        DistributedRateLimiter limiter = new DistributedRateLimiter(provider, true, 10, 1, 5, 1, 10, 1);

        assertThat(limiter.tryAcquire("chat")).isTrue();
    }

    @Test
    void tryAcquireReturnsTrueWhenRedissonThrows() {
        ObjectProvider<RedissonClient> provider = mock(ObjectProvider.class);
        RedissonClient client = mock(RedissonClient.class);
        when(provider.getIfAvailable()).thenReturn(client);
        when(client.getRateLimiter(anyString())).thenThrow(new RuntimeException("connection refused"));
        DistributedRateLimiter limiter = new DistributedRateLimiter(provider, true, 10, 1, 5, 1, 10, 1);

        assertThat(limiter.tryAcquire("chat")).isTrue();
    }

    @Test
    void tryAcquireReturnsTrueWhenTokenAcquired() {
        ObjectProvider<RedissonClient> provider = mock(ObjectProvider.class);
        RedissonClient client = mock(RedissonClient.class);
        RRateLimiter rateLimiter = mock(RRateLimiter.class);
        when(provider.getIfAvailable()).thenReturn(client);
        when(client.getRateLimiter("ratelimit:ai:chat")).thenReturn(rateLimiter);
        when(rateLimiter.tryAcquire(1)).thenReturn(true);
        DistributedRateLimiter limiter = new DistributedRateLimiter(provider, true, 10, 1, 5, 1, 10, 1);

        assertThat(limiter.tryAcquire("chat")).isTrue();
        verify(rateLimiter).trySetRate(any(), anyLong(), anyLong(), any());
        verify(rateLimiter).tryAcquire(1);
    }

    @Test
    void tryAcquireReturnsFalseWhenTokenExhausted() {
        ObjectProvider<RedissonClient> provider = mock(ObjectProvider.class);
        RedissonClient client = mock(RedissonClient.class);
        RRateLimiter rateLimiter = mock(RRateLimiter.class);
        when(provider.getIfAvailable()).thenReturn(client);
        when(client.getRateLimiter("ratelimit:ai:chat")).thenReturn(rateLimiter);
        when(rateLimiter.tryAcquire(1)).thenReturn(false);
        DistributedRateLimiter limiter = new DistributedRateLimiter(provider, true, 10, 1, 5, 1, 10, 1);

        assertThat(limiter.tryAcquire("chat")).isFalse();
    }

    @Test
    void tryAcquireReturnsTrueForUnknownChannel() {
        ObjectProvider<RedissonClient> provider = mock(ObjectProvider.class);
        RedissonClient client = mock(RedissonClient.class);
        when(provider.getIfAvailable()).thenReturn(client);
        DistributedRateLimiter limiter = new DistributedRateLimiter(provider, true, 10, 1, 5, 1, 10, 1);

        assertThat(limiter.tryAcquire("unknown")).isTrue();
    }

    @Test
    void isActiveReturnsTrueWhenEnabledAndClientAvailable() {
        ObjectProvider<RedissonClient> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(mock(RedissonClient.class));
        DistributedRateLimiter limiter = new DistributedRateLimiter(provider, true, 10, 1, 5, 1, 10, 1);

        assertThat(limiter.isActive()).isTrue();
    }

    @Test
    void isActiveReturnsFalseWhenDisabled() {
        ObjectProvider<RedissonClient> provider = mock(ObjectProvider.class);
        DistributedRateLimiter limiter = new DistributedRateLimiter(provider, false, 10, 1, 5, 1, 10, 1);

        assertThat(limiter.isActive()).isFalse();
    }

    @Test
    void isActiveReturnsFalseWhenClientUnavailable() {
        ObjectProvider<RedissonClient> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);
        DistributedRateLimiter limiter = new DistributedRateLimiter(provider, true, 10, 1, 5, 1, 10, 1);

        assertThat(limiter.isActive()).isFalse();
    }
}

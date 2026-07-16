package com.knowsource.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

/**
 * Redis 配置。
 *
 * <p>设计要点：
 * <ul>
 *   <li>{@code RedissonClient} / {@code RedissonConnectionFactory} / {@code StringRedisTemplate}
 *       由 {@code redisson-spring-boot-starter} 自动配置，本类不重复声明。</li>
 *   <li>仅自定义 {@code RedisTemplate<String, Object>}：key 用 StringRedisSerializer，
 *       value 用 GenericJackson2JsonRedisSerializer（携带类型信息，支持 record 反序列化）。</li>
 *   <li>整个配置类在 {@code knowsource.redis.enabled=false} 时不生效，
 *       {@code CacheService} 的 {@code ObjectProvider} 取不到 bean → 降级为 noop。</li>
 * </ul>
 */
@Configuration
@ConditionalOnProperty(prefix = "knowsource.redis", name = "enabled", havingValue = "true")
public class RedisConfig {

    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory connectionFactory) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);
        template.setKeySerializer(new StringRedisSerializer());
        template.setValueSerializer(new GenericJackson2JsonRedisSerializer());
        template.setHashKeySerializer(new StringRedisSerializer());
        template.setHashValueSerializer(new GenericJackson2JsonRedisSerializer());
        return template;
    }
}

# KnowSource Redis 引入设计文档

> **状态**：📋 设计阶段，**尚未实施**（仅计划，无任何 Redis 代码改动落地）
> **范围**：P0（分布式限流，修复多实例正确性 bug）+ P1（用户缓存、KB 成员缓存，消除热路径 DB 查询）
> **底层选型**：Redisson（`RRateLimiter` / `RSemaphore` / `RBucket`）
> **创建日期**：2026-07-05
> **关联文档**：`docs/architecture/KnowSource-RAG-架构设计文档.md`、`代码修复待办清单-P0-P3.md`

---

## 1. 背景与动机

KnowSource 当前是**完全无缓存的单机架构**：唯一的持久化层是 PostgreSQL（含 pgvector），没有任何 Caffeine / Redis / Spring Cache 抽象。整个 `src/main` 目录中字符串 "redis" 出现次数为 0。

这种"全 stateless 计算 + 单一 RDBMS"的架构在单实例下工作良好，但存在两类隐患：

### 1.1 P0 —— AI 限流是"多实例必坏"的正确性问题

`ai/AiProviderResilience.java` 使用 resilience4j 的 `RateLimiter.of(...)` / `Bulkhead.of(...)`，二者**都是 JVM 本地的原子计数器**。配置为 chat 10 req/s、embedding 5 req/s、rerank 10 req/s（见 `application.yml:160-184`）。

**危害**：一旦水平扩到 N 个实例，向 DashScope 的实际 QPS = N × 配置值，会触发供应商限流甚至超额计费。这不是性能优化问题，而是**分布式语义错误**。

### 1.2 P1 —— 热路径上"每个请求都打 DB"

- **`security/CurrentUserService.findByUsername`（line 38-48）**：`JwtAuthenticationFilter` 对每个鉴权请求都调用它执行 `SELECT id, username, global_role, token_version FROM users WHERE username = :username`。这是全系统最高频的 DB 命中。
- **`kb/KnowledgeBaseService` 的成员校验**（`requireKbMember` line 282、`requireKbOwnerOrAdmin` line 352、`memberRole` line 392）：在 `KnowledgeBaseService`、`DocumentService`、`ChatService`、`ChatSessionService`、`QaTraceService` 中反复执行 `SELECT ... FROM kb_members`，几乎覆盖所有业务接口。

P1 的目标是把这两类查询的 DB 频率从"每请求一次"降为"每 TTL 周期一次"。

---

## 2. 总体设计原则

| 原则 | 说明 |
|---|---|
| **不引入 Spring Cache 抽象**（`@Cacheable`） | 项目从未用过它；手写 `RedisTemplate` 调用对失效控制更精确，与现有 `@Value` 注入风格一致 |
| **Redis 不可用时优雅降级** | 所有 Redis 调用集中在 `CacheService`；异常一律 catch → fallthrough 到原 DB / 本地兜底。Redis 宕机不阻塞业务 |
| **分布式限流从 `AiProviderResilience` 解耦** | 抽出独立 `DistributedRateLimiter`，通过 `ObjectProvider` 注入。`AiProviderResilience` 构造签名不变，**现有 `AiProviderResilienceTest` 的 plain-JUnit 测试零改动即可编译** |
| **保留本地 resilience4j 作为兜底** | Redis 故障时仍有限流能力，不会雪崩到供应商 |
| **失效精确、窗口可接受** | 用户角色变更 30s、KB 成员变更 60s 的最终一致窗口；鉴权安全仍由 `token_version` 机制兜底 |

---

## 3. 架构总览

```
                        ┌─────────────────────────────────────┐
                        │         Redis (Redisson)            │
                        │  ratelimit:ai:*  semaphore:ai:*     │
                        │  user:*         kbmember:kbId:uid   │
                        └───────────▲────────────▲────────────┘
                                    │            │
              ┌─────────────────────┘            └────────────────────┐
              │                                                       │
   ┌──────────────────────┐                              ┌──────────────────────┐
   │ DistributedRateLimiter│                              │    CacheService      │
   │ (ObjectProvider<RC>)  │                              │ (ObjectProvider<RT>) │
   └──────────▲───────────┘                              └──────▲───────────────┘
              │                                                 │
   ┌──────────┴───────────┐                       ┌────────────┴────────────┐
   │ AiProviderResilience  │           ┌───────────┴──────────┐  ┌──────────┴──────────┐
   │  分布式层 → 本地 Bulk  │           │ CurrentUserService   │  │ KnowledgeBaseService│
   │  head → 本地 RL → 业务 │           │ (+ user 缓存)        │  │ (+ kb 成员缓存)     │
   └───────────────────────┘           └───────────┬──────────┘  └──────────┬──────────┘
                                                   │                        │
                                       ┌───────────┴──────────┐   ┌──────────┴──────────┐
                                       │ AuthService          │   │ add/update/remove/  │
                                       │ updateGlobalRole()   │   │ delete 失效点       │
                                       │ → evict user:<name>  │   │ → evict kbmember:*  │
                                       └──────────────────────┘   └─────────────────────┘
```

---

## 4. 基础设施改动

### 4.1 `pom.xml`（新增 1 个依赖）

```xml
<dependency>
    <groupId>org.redisson</groupId>
    <artifactId>redisson-spring-boot-starter</artifactId>
    <version>3.27.2</version> <!-- 对齐 Spring Boot 3.5.x -->
</dependency>
```

> redisson-spring-boot-starter 已传递依赖 `spring-boot-starter-data-redis` 和 Lettuce，**不需要**再单独引入。

### 4.2 `docker-compose.yml`（新增 redis 服务，仿 postgres 风格）

```yaml
services:
  postgres:
    # ... 现有不变 ...

  redis:
    image: redis:7-alpine
    container_name: knowsource-redis
    ports: ["16379:6379"]              # 仿 postgres 用 15432 的非默认端口套路
    volumes: [knowsource-redis-data:/data]
    healthcheck:
      test: ["CMD", "redis-cli", "ping"]
      interval: 5s
      timeout: 3s
      retries: 20

volumes:
  knowsource-postgres-data:
  knowsource-redis-data:               # 新增
```

### 4.3 `.env.example`（新增 3 个变量，遵循 `KNOWSOURCE_*` 命名）

```bash
# Redis
KNOWSOURCE_REDIS_ENABLED=true
KNOWSOURCE_REDIS_URL=redis://localhost:16379
KNOWSOURCE_REDIS_PASSWORD=
```

### 4.4 `application.yml`

在 `knowsource:` 下新增 `redis:` / `cache:` 子树，并补 `spring.data.redis.*`：

```yaml
spring:
  data:
    redis:
      url: ${KNOWSOURCE_REDIS_URL:redis://localhost:16379}

knowsource:
  redis:
    enabled: ${KNOWSOURCE_REDIS_ENABLED:true}
    url: ${KNOWSOURCE_REDIS_URL:redis://localhost:16379}
    password: ${KNOWSOURCE_REDIS_PASSWORD:}
  cache:
    user-cache-enabled: true
    user-ttl-seconds: 30               # CurrentUserService 缓存
    kb-member-cache-enabled: true
    kb-member-ttl-seconds: 60          # KB 成员缓存
```

### 4.5 `application-db.yml`（测试 profile 处理）

在现有 `spring.autoconfigure.exclude` 列表里追加（**仿照现有排除 pgvector/openai autoconfig 的写法**），让纯单元/集成测试默认不依赖 Redis：

```yaml
spring:
  autoconfigure:
    exclude:
      # ... 现有项 ...
      - org.redisson.spring.starter.RedissonAutoConfiguration
```

集成测试若要验证 Redis，通过 `KNOWSOURCE_REDIS_ENABLED=true` + `KNOWSOURCE_REDIS_URL` 指向测试环境的 Redis；测试间用 `cacheService.flushDb()` 清理（仿现有 `@BeforeEach` 的 `DELETE FROM ...`）。

---

## 5. 新增类（4 个）

### 5.1 `src/main/java/com/knowsource/config/RedisConfig.java`

仿 `AsyncConfig` / `AiHttpClientConfig` 的 `@Configuration` + `@Value` 风格。

```java
@Configuration
@ConditionalOnProperty(prefix = "knowsource.redis", name = "enabled", havingValue = "true")
public class RedisConfig {

    @Bean(destroyMethod = "shutdown")
    public RedissonClient redissonClient(
            @Value("${knowsource.redis.url}") String url,
            @Value("${knowsource.redis.password:}") String password) {
        Config config = new Config();
        // 单节点；如需哨兵/集群可扩展为按 url scheme 分支
        SingleServerConfig single = config.useSingleServer()
                .setAddress(url)
                .setConnectionPoolSize(32)
                .setConnectionMinimumIdleSize(8);
        if (!password.isBlank()) {
            single.setPassword(password);
        }
        return Redisson.create(config);
    }

    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedissonClient redissonClient) {
        // Redisson 提供 RedissonConnectionFactory
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(redissonClient.getConnectionManager().getConnectionFactory());
        template.setKeySerializer(new StringRedisSerializer());
        template.setValueSerializer(new GenericJackson2JsonRedisSerializer());
        return template;
    }

    @Bean
    public StringRedisTemplate stringRedisTemplate(RedissonClient redissonClient) {
        return new StringRedisTemplate(redissonClient.getConnectionManager().getConnectionFactory());
    }
}
```

> 实施时需验证 `RedissonClient` → `RedisConnectionFactory` 的桥接 API（Redisson 自带 `RedissonConnectionFactory`，Spring Boot autoconfig 已有支持；若直接用 starter 也可省去手写 `RedisTemplate` bean，由 starter 自动配置——实施时二选一更简洁的方案）。

### 5.2 `src/main/java/com/knowsource/cache/CacheKeys.java`

集中所有 key 前缀常量，避免散落字符串。

```java
public final class CacheKeys {
    private CacheKeys() {}

    /** 用户缓存：user:<username> */
    public static String user(String username) {
        return "user:" + username;
    }

    /** KB 成员角色缓存：kbmember:<kbId>:<userId> */
    public static String kbMember(String kbId, long userId) {
        return "kbmember:" + kbId + ":" + userId;
    }

    /** KB 删除时批量清理：kbmember:<kbId>:* */
    public static String kbMemberPattern(String kbId) {
        return "kbmember:" + kbId + ":*";
    }

    /** AI 限流（按 channel：chat / embedding / rerank） */
    public static final String RATE_LIMIT_PREFIX = "ratelimit:ai:";

    /** AI 并发舱壁信号量 */
    public static final String SEMAPHORE_PREFIX = "semaphore:ai:";
}
```

### 5.3 `src/main/java/com/knowsource/cache/CacheService.java`

**集中封装所有 Redis 读写 + 降级**。所有业务代码只调它，绝不直接碰 `RedisTemplate`。

```java
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
        if (!enabled) return Optional.empty();
        try {
            RedisTemplate<String, Object> redis = redisProvider.getIfAvailable();
            if (redis == null) return Optional.empty();
            Object value = redis.opsForValue().get(key);
            return value == null ? Optional.empty() : Optional.of(type.cast(value));
        } catch (Exception ex) {
            log.warn("Redis get failed for key={}, falling through to DB: {}", key, ex.toString());
            return Optional.empty();
        }
    }

    /** 写：异常吞掉 + log.warn */
    public <T> void put(String key, T value, Duration ttl) {
        if (!enabled) return;
        try {
            RedisTemplate<String, Object> redis = redisProvider.getIfAvailable();
            if (redis == null) return;
            redis.opsForValue().set(key, value, ttl);
        } catch (Exception ex) {
            log.warn("Redis put failed for key={}: {}", key, ex.toString());
        }
    }

    /** 删：异常吞掉 */
    public void evict(String key) {
        if (!enabled) return;
        try {
            RedisTemplate<String, Object> redis = redisProvider.getIfAvailable();
            if (redis != null) redis.delete(key);
        } catch (Exception ex) {
            log.warn("Redis evict failed for key={}: {}", key, ex.toString());
        }
    }

    /** 批量按 pattern 删（KB 删除时清整个 kb 的成员缓存） */
    public void evictByPattern(String pattern) {
        if (!enabled) return;
        try {
            RedisTemplate<String, Object> redis = redisProvider.getIfAvailable();
            if (redis == null) return;
            Set<String> keys = redis.keys(pattern);
            if (keys != null && !keys.isEmpty()) redis.delete(keys);
        } catch (Exception ex) {
            log.warn("Redis evictByPattern failed for pattern={}: {}", pattern, ex.toString());
        }
    }

    /** 测试用 */
    public void flushDb() {
        try {
            RedisTemplate<String, Object> redis = redisProvider.getIfAvailable();
            if (redis != null) redis.getConnectionFactory().getConnection().flushDb();
        } catch (Exception ignored) { }
    }
}
```

### 5.4 `src/main/java/com/knowsource/ai/DistributedRateLimiter.java`

封装 Redisson 的 `RRateLimiter` + `RSemaphore`。Redis 没启用时通过 `ObjectProvider` 注入为 null，调用一律放行（降级）。

```java
@Slf4j
@Component
public class DistributedRateLimiter {

    private final ObjectProvider<RedissonClient> redissonProvider;
    private final boolean enabled;

    // 各 channel 的限流配置（与 application.yml 的 knowsource.ai.resilience.* 对齐）
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
                "chat",      new RateLimitSpec(chatLimit,   Duration.ofSeconds(chatPeriod)),
                "embedding", new RateLimitSpec(embedLimit,  Duration.ofSeconds(embedPeriod)),
                "rerank",    new RateLimitSpec(rerankLimit, Duration.ofSeconds(rerankPeriod)));
    }

    /**
     * 尝试获取一个令牌。
     * @return true = 获取成功可继续；false = 被限流（调用方应抛限流异常）。
     *         Redis 不可用/未启用 → 一律返回 true（放行），由本地 resilience4j 兜底。
     */
    public boolean tryAcquire(String channel) {
        if (!enabled) return true;
        RedissonClient client = redissonProvider.getIfAvailable();
        if (client == null) return true;

        try {
            RateLimitSpec spec = specs.get(channel);
            RRateLimiter limiter = client.getRateLimiter(CacheKeys.RATE_LIMIT_PREFIX + channel);
            // 令牌桶：每个 refresh 周期补充 limit 个令牌
            limiter.trySetRate(RateType.OVERALL, spec.limit(), spec.period().toMillis(), RateIntervalUnit.MILLISECONDS);
            return limiter.tryAcquire(1);
        } catch (Exception ex) {
            log.warn("Distributed rate limiter failed for channel={}, allowing (local fallback): {}",
                    channel, ex.toString());
            return true; // 降级放行
        }
    }

    /** 仅 Redis 可达时返回 true（用于决定是否走分布式路径） */
    public boolean isActive() {
        return enabled && redissonProvider.getIfAvailable() != null;
    }

    private record RateLimitSpec(long limit, Duration period) {}
}
```

> **并发舱壁（Bulkhead）的分布式化**：可选方案是用 Redisson `RSemaphore`（`semaphore:ai:<channel>`，初始化为 `max-concurrent-calls`）。但 Bulkhead 的分布式化收益小于限流（供应商限流是硬约束，并发更多是性能调优），**P0 阶段建议先只做分布式限流，Bulkhead 仍保留本地**。如需扩展，在 `DistributedRateLimiter` 加 `tryAcquirePermit(channel)` / `releasePermit(channel)` 即可。

---

## 6. 改动类（4 个业务类）

### 6.1 `ai/AiProviderResilience.java`（P0 核心）

**保留现有 resilience4j 本地 `RateLimiter` / `Bulkhead` 不删**，作为 Redis 不可用时的本地兜底。改动：

1. 构造函数新增 `ObjectProvider<DistributedRateLimiter>` 参数。
   - **关键**：这样 plain-JUnit 的 `AiProviderResilienceTest`（直接 `new AiProviderResilience(10,1,0,...)`）仍可省略/传 null，**现有 3 个测试零改动即可编译**。
2. `execute(...)` 装饰链改为：

```
分布式限流(Redisson, 若启用) → 本地 Bulkhead → 本地 RateLimiter(兜底) → Retry(仅 embedding) → 业务
```

伪代码：

```java
private <T> T execute(String errorMsg, Callable<T> callable,
                      RateLimiter localRateLimiter, Bulkhead bulkhead, Retry retry) {
    // 1. 分布式限流（Redisson）
    String channel = channelOf(localRateLimiter);  // chat / embedding / rerank
    DistributedRateLimiter dist = distRateLimiterProvider.getIfAvailable();
    if (dist != null && dist.isActive()) {
        if (!dist.tryAcquire(channel)) {
            throw new AiProviderException("Distributed rate limit exceeded for " + channel);
        }
    }
    // 2. 本地 Bulkhead + 本地 RateLimiter + Retry（沿用现状）
    Supplier<T> decorated = Bulkhead.decorateSupplier(bulkhead,
            RateLimiter.decorateSupplier(localRateLimiter, () -> callable(callable)));
    if (retry != null) {
        decorated = Retry.decorateSupplier(retry, decorated);
    }
    try {
        return decorated.get();
    } catch (Resilience4jHttpException | RuntimeException ex) {
        throw new AiProviderException(errorMsg, ex);
    }
}
```

3. 分布式层抛异常/未启用 → 自动降级到纯本地链路，行为与现状完全一致。
4. **7 个消费者无需改动**（仍注入 `AiProviderResilience`）。

### 6.2 `security/CurrentUserService.java`（P1 用户缓存）

- 构造函数新增：`CacheService cacheService`、`@Value("${knowsource.cache.user-cache-enabled:true}") boolean cacheEnabled`、`@Value("${knowsource.cache.user-ttl-seconds:30}") long ttl`。
- `findByUsername` 改为：

```java
public CurrentUser findByUsername(String username) {
    if (cacheEnabled) {
        Optional<CurrentUser> cached = cacheService.get(CacheKeys.user(username), CurrentUser.class);
        if (cached.isPresent()) return cached.get();
    }
    CurrentUser user = jdbcClient.sql("""
            SELECT id, username, global_role, token_version
            FROM users
            WHERE username = :username
            """)
            .param("username", username)
            .query(CurrentUserService::mapUser)
            .optional()
            .orElseThrow(() -> new AuthenticationCredentialsNotFoundException("Authenticated user not found."));
    if (cacheEnabled) {
        cacheService.put(CacheKeys.user(username), user, Duration.ofSeconds(ttl));
    }
    return user;
}
```

- **不缓存负值**（用户不存在时抛异常，不入缓存），避免缓存不存在用户名。
- `CurrentUser` 是 record（`long id, String username, String global_role, int token_version`），JSON 序列化无障碍。

### 6.3 `auth/AuthService.java`（P1 失效点）

- 注入 `CacheService`。
- **唯一影响 `users` 表的写点是 `updateGlobalRole`（line 182-186）**，同时修改 `global_role` 和 `token_version`。执行成功后：

```java
cacheService.evict(CacheKeys.user(username));
```

- `register`（line 81）和 `registerFirstAdmin`（line 162）是 INSERT 新用户，键不存在，**无需失效**。

### 6.4 `kb/KnowledgeBaseService.java`（P1 成员缓存）

- 注入 `CacheService` + 两个 TTL 配置。
- 新增私有方法：

```java
private Optional<String> cachedMemberRole(String kbId, long userId) {
    if (!kbMemberCacheEnabled) {
        // 直查 DB
        return Optional.ofNullable(rawMemberRole(kbId, userId));
    }
    Optional<String> cached = cacheService.get(CacheKeys.kbMember(kbId, userId), String.class);
    if (cached.isPresent()) return cached;
    String role = rawMemberRole(kbId, userId);  // 原 SQL：SELECT role FROM kb_members WHERE ...
    if (role != null) cacheService.put(CacheKeys.kbMember(kbId, userId), role, Duration.ofSeconds(kbMemberTtl));
    return Optional.ofNullable(role);
}
```

- 三个检查点改为先调 `cachedMemberRole`：
  - `requireKbMember`（line 282）—— 仅非 ADMIN 路径查缓存；ADMIN 走 `knowledge_bases` 存在性检查，与成员无关
  - `requireKbOwnerOrAdmin`（line 352）
  - `memberRole`（line 392）

- **失效点（4 处）**：

| 方法 | 行号 | 失效调用 |
|---|---|---|
| `addMember`（upsert，可能改角色） | 190 | `evict(CacheKeys.kbMember(kbId, memberUserId))` |
| `updateMember` | 210 | `evict(CacheKeys.kbMember(kbId, userId))` |
| `removeMember` | 234 | `evict(CacheKeys.kbMember(kbId, userId))` |
| `delete(String kbId)`（line 163 级联删 kb_members） | 111-173 | `evictByPattern(CacheKeys.kbMemberPattern(kbId))` |

---

## 7. 测试策略

### 7.1 现有测试不破坏

| 测试 | 影响 |
|---|---|
| `AiProviderResilienceTest`（plain JUnit，`new AiProviderResilience(10,1,0,...)`） | `DistributedRateLimiter` 走 `ObjectProvider` 注入，构造可省略/传 null，**3 个测试零改动编译通过** |
| `AuthSecurityTest` / `KnowledgeBaseControllerTest` 等 `@SpringBootTest` + `db` profile | `application-db.yml` 默认禁用 Redisson autoconfig → 走本地兜底/直查 DB 路径，**行为与现状一致** |

### 7.2 新增测试

1. **`src/test/java/com/knowsource/cache/CacheServiceTest.java`**（plain JUnit + Mockito）
   - mock `RedisTemplate`，验证：get 命中/miss、put/evict、Redis 抛异常时降级返回 `Optional.empty()`、`enabled=false` 时全 noop。
2. **`src/test/java/com/knowsource/ai/DistributedRateLimiterTest.java`**（plain JUnit + Mockito）
   - mock `RedissonClient` / `RRateLimiter`，验证：Redis 不可用时 `tryAcquire` 返回 true（降级放行）；可用且令牌耗尽时返回 false。
3. **集成验证**：在 `AuthSecurityTest` / `KnowledgeBaseControllerTest` 中新增用例（`db` profile 下若 `KNOWSOURCE_REDIS_ENABLED=true` 则断言缓存命中），`@BeforeEach` 调 `cacheService.flushDb()` 仿现有 DB 清理。

---

## 8. 向后兼容 / 降级矩阵

| 场景 | 行为 |
|---|---|
| `knowsource.redis.enabled=false` | `RedissonClient` bean 不创建；`CacheService.enabled=false`；`DistributedRateLimiter.isActive()=false` → **完全等价于现状** |
| Redis 启动时连不上 | Redisson lazy 连接，首次调用抛异常 → `CacheService` / `DistributedRateLimiter` 捕获降级 → 业务走 DB / 本地限流 |
| Redis 运行中断开 | 单次调用 catch 异常 → fallthrough；后续自动重连 |
| 用户角色变更的缓存窗口 | 最多 30s 内读到旧 `global_role` |
| KB 成员变更的缓存窗口 | 最多 60s 内读到旧角色 |
| 现有 plain-JUnit 单元测试 | 不受影响（`ObjectProvider` 注入传 null） |
| 现有 `db` profile 集成测试 | 默认禁用 Redisson → 走本地兜底，行为不变 |

### 安全说明（关键）

用户缓存只缓存读路径的展示字段（`id / username / global_role / token_version`）。**真正的鉴权决策仍走 `token_version` 比较**——缓存 30s 内即使角色变了，旧 JWT 在 access-token TTL（900s）内本就有效（这是项目现有设计，不是新引入的窗口）。Redis 只是把"每个请求查一次 users 表"降为"每 30s 查一次"，**不改变任何鉴权语义**。

---

## 9. 改动文件清单

### 新增（4 类 + 2 测试）

| 文件 | 作用 |
|---|---|
| `src/main/java/com/knowsource/config/RedisConfig.java` | Redisson `RedissonClient` + `RedisTemplate` beans |
| `src/main/java/com/knowsource/cache/CacheKeys.java` | 集中 key 前缀常量 |
| `src/main/java/com/knowsource/cache/CacheService.java` | Redis 读写 + 降级封装 |
| `src/main/java/com/knowsource/ai/DistributedRateLimiter.java` | Redisson `RRateLimiter` 封装 |
| `src/test/java/com/knowsource/cache/CacheServiceTest.java` | CacheService 单元测试 |
| `src/test/java/com/knowsource/ai/DistributedRateLimiterTest.java` | DistributedRateLimiter 单元测试 |

### 改动（4 配置 + 4 业务类）

| 文件 | 改动 |
|---|---|
| `pom.xml` | + `redisson-spring-boot-starter` |
| `docker-compose.yml` | + redis 服务 + volume |
| `.env.example` | + `KNOWSOURCE_REDIS_*` |
| `src/main/resources/application.yml` | + `spring.data.redis.*`、`knowsource.redis.*`、`knowsource.cache.*` |
| `src/main/resources/application-db.yml` | `spring.autoconfigure.exclude` 追加 `RedissonAutoConfiguration` |
| `src/main/java/com/knowsource/ai/AiProviderResilience.java` | 构造加 `ObjectProvider<DistributedRateLimiter>`；`execute` 链加分布式限流 |
| `src/main/java/com/knowsource/security/CurrentUserService.java` | `findByUsername` 加 user 缓存 |
| `src/main/java/com/knowsource/auth/AuthService.java` | `updateGlobalRole` 后 `evict user:<name>` |
| `src/main/java/com/knowsource/kb/KnowledgeBaseService.java` | + 成员缓存 + 4 处失效 |

---

## 10. 实施顺序（每步可独立编译、验证、回滚）

1. **基础设施**：`pom.xml` → `docker-compose.yml` → `.env.example` → `application.yml` / `application-db.yml`
2. **缓存底座**：`RedisConfig` + `CacheKeys` + `CacheService`（含降级封装）+ `CacheServiceTest`
3. **P0**：`DistributedRateLimiter` + 改 `AiProviderResilience` → 跑 `AiProviderResilienceTest` 确认仍绿
4. **P1 用户**：`CurrentUserService` 缓存 + `AuthService.updateGlobalRole` 失效
5. **P1 KB**：`KnowledgeBaseService` 缓存 + 4 处失效
6. **集成验证**：本地起 `docker-compose up`（含新 redis），跑 `AuthSecurityTest` / `KnowledgeBaseControllerTest`，加 1-2 个缓存命中断言

**一键回滚开关**：`knowsource.redis.enabled=false` 即可让所有 Redis 代码退化为 noop，等价于当前现状。

---

## 11. 后续可扩展方向（本次不做）

| 优先级 | 方向 | 锚点 |
|---|---|---|
| P2 | query embedding 缓存（省 AI 调用） | `chat/VectorSearchService.java:87` `embeddingGateway.embedQuery(...)` |
| P2 | 文档分块 embedding 缓存（重试零成本） | `index/VectorIndexService.java:123` `embedDocuments(batchTexts)` |
| P2 | LLM 答案 / 改写 / 重排结果缓存 | `chat/QueryRewriteService`、`SpringAiAnswerGenerator`、`DashScopeRerankClient` |
| P3 | refresh token Redis 化 + JWT 黑名单（立即登出） | `auth/AuthService.java:106` |
| P3 | poller 领导者选举（减 DB 空轮询） | `index/DocumentIndexEventPoller.java:19` `AtomicBoolean` |
| P3 | eval 状态 pub/sub 替代忙轮询 | `eval/EvalRunnerService.waitForIngestReady` |

P2 的 embedding 缓存需要包装 `DocumentEmbeddingGateway` 接口（query/doc 两个方向分别缓存，键需按内容 hash + 方向分区），改动稍大；建议在 P0/P1 稳定运行后再评估。

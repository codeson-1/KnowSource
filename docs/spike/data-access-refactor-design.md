# 数据访问与检索分层改进方案

> Status: Draft（待评审）
> Owner: Retrieval / Persistence
> 关联：`ADR-001-production-rag-path.md`、`hybrid-search-design.md`、`KnowSource-RAG-架构设计文档.md`

## 1. 现状与问题

对全代码库的 SQL 与数据访问做了完整盘点（103 个 `.sql(` 执行点，13 个类，零 ORM/Repository 抽象），归纳出三类问题：

### 1.1 重复与散落

| 重复项 | 出现次数 | 位置 |
|--------|---------|------|
| membership 存在性检查 `SELECT COUNT(*) FROM kb_members WHERE kb_id=? AND user_id=?` | 5 | `DocumentService` / `ChatService` / `ChatSessionService` / `QaTraceService` / `KnowledgeBaseService` 各一份 `requireKbMember` |
| 文档详情 4-LATERAL SELECT（`DOCUMENT_SELECT_COLUMNS`） | 4 | `DocumentService:196/235/447/1055`，仅末尾 WHERE 不同 |
| ingest-task 状态 UPDATE（含 10 个 quality 列 + quality_report） | 4 | `markIngestParsing/Pending/Failed`、`persistChunksAndMarkReady` |
| 文档生命周期 UPDATE（index_status / vectors_synced_at） | 5+ | `DocumentIndexOutboxService` SYNCING/SYNCED/FAILED、`requeueFailedEvent`、`DocumentService.createPublishEvent/archive` |
| 级联删除 | 2 组 | `DocumentService.deleteDocument`（5 条 DELETE）、`KnowledgeBaseService.delete`（11 条 DELETE） |

### 1.2 业务逻辑与数据访问混杂

每个 Service 类同时承担三件事：业务规则编排、SQL 字符串拼接、ResultSet → record 手工映射。例：`DocumentService`（28 处 SQL）、`KnowledgeBaseService`（26 处 SQL）几乎没有"纯业务逻辑"的段落，可读性与可测性都受损。无 Repository/DAO 边界 → 无法对"数据访问"单独测试或替换。

### 1.3 复杂检索 SQL 不可组合

向量检索 SQL（`VectorSearchService:95-123`）、4-LATERAL 文档查询、outbox 的可写 CTE + `FOR UPDATE SKIP LOCKED` —— 这些 PG 特有 SQL 是**一整段字符串**。要加一个元数据过滤条件、或叠加混合检索，必须复制整段 SQL 再改。检索逻辑无法像积木一样组合（这正是 `hybrid-search-design.md` 里要新增 `KeywordSearchService` 时感到别扭的根因）。

### 1.4 次要：事务管理不一致

`TransactionTemplate`（programmatic）为主，但 `KnowledgeBaseService.create` 孤零零一个 `@Transactional`（`:37`），同类的其他方法却用 `TransactionTemplate`。混合使用是维护陷阱（自调用不触发注解、行为差异）。全程未用 `@Transactional(readOnly=true)`。

## 2. 技术选型与推荐

### 2.1 数据访问框架：**引入 Spring Data JDBC（轻量），不引入 JPA**

| 维度 | Spring Data JDBC | JPA / Hibernate | 维持纯 JdbcClient + 自建 DAO |
|------|-----------------|-----------------|------------------------------|
| 与现有 record 风格契合度 | ✅ 高（注解 + record/不可变对象） | ❌ 低（实体可变、状态机、setter） | ✅ 高 |
| Lazy-loading / N+1 魔法 | ✅ **无**（显式 fetch） | ❌ 有，高频陷阱 | ✅ 无 |
| 简单 CRUD 简化 | ✅ `CrudRepository` 声明式 | ✅ 声明式 | ❌ 仍手写 |
| 复杂/PG 特有 SQL | ✅ `@Query` 原生 SQL 无损保留 | ⚠️ 可用但 JPQL/HQL 抽象别扭 | ✅ 原生 |
| 迁移成本 | 中（加 starter + 为 CRUD 表建 Repository） | **高**（重写实体/关系/级联，与 record 冲突） | 低（抽接口） |
| 新依赖 | `spring-boot-starter-data-jdbc` | `spring-boot-starter-data-jpa` + Hibernate | 无 |
| 风险 | 低 | 高（session/缓存/级联语义复杂） | 低 |

**推荐 Spring Data JDBC 的理由：**
1. **风格同构**。项目已全面采用 `record`（`RetrievedChunk`、`ChunkForIndex` 等），Spring Data JDBC 原生支持 record 作为持久化对象，无强制无参构造/setter。JPA 的可变实体 + 状态机与现状冲突最大。
2. **无魔法**。Spring Data JDBC 没有 lazy-loading、没有一级缓存、没有 dirty checking，"查什么就是什么"，与现有手写 SQL 的可预测性一致。JPA 的这些特性在团队不熟时是 bug 源。
3. **复杂检索无损保留**。向量检索、CTE、`FOR UPDATE SKIP LOCKED` 用 `@Query(nativeQuery=true)` 或继续走 `JdbcClient` 原样保留——Spring Data JDBC 不强制把所有查询塞进 ORM 抽象。
4. **与 starter-jdbc 共存**。`spring-boot-starter-data-jdbc` 与现有 `spring-boot-starter-jdbc` 共存，`JdbcClient`/`TransactionTemplate` 仍可用，可**渐进迁移**而非大爆炸。

> **不推荐 JPA**：迁移成本最高（重写实体关系/级联），收益最虚（你的复杂检索仍得绕过它写原生 SQL），且 lazy-loading/N+1 在当前"全 context 测试 + 直接 JSONB 断言"的测试模式下会成为隐性失败源。

### 2.2 检索引擎层：**不迁外部引擎，引入"检索查询层"抽象**

| 选项 | 判断 |
|------|------|
| 迁 Elasticsearch / OpenSearch | ❌ 换运维栈，需双写双查、数据同步管道，无明确数据量瓶颈时不划算；且 pgvector 已覆盖向量 |
| 迁 ParadeDB（BM25） | △ 作为 `hybrid-search-design.md` 的**演进选项**保留，不作为本次主路径 |
| 迁 Milvus / Qdrant（专用向量库） | ❌ 同上，PG+HNSW 当前足够 |
| **引入检索查询层抽象（推荐）** | ✅ 把"检索 SQL 字符串"升级为"可组合的检索请求 + 候选策略"，复杂 SQL 仍是原生但可组合 |

**推荐理由：** 当前痛点不是"PG 检索能力不足"（混合检索方案已用 tsvector+GIN 补齐关键词路），而是"检索 SQL 不可组合"。引入外部引擎是换问题（多了一套运维），引入查询层抽象是治问题（让检索可扩展）。

## 3. 目标架构

```
┌─────────────────────────────────────────────────────────────┐
│  Service 层（业务逻辑编排，不含 SQL）                        │
│  ChatService / DocumentService / KnowledgeBaseService ...   │
└───────────┬───────────────────────────────┬─────────────────┘
            │ CRUD/查询                      │ 检索
            ▼                               ▼
┌───────────────────────────┐   ┌─────────────────────────────┐
│ Repository 层（新增）       │   │ 检索查询层（新增）            │
│ Spring Data JDBC           │   │ RetrievalRequest（可组合）    │
│ CrudRepository / @Query    │   │ CandidateSource（策略）       │
│ 简单表：users/kbs/docs/    │   │  ├ VectorCandidateSource     │
│ chunks/members/sessions... │   │  ├ KeywordCandidateSource    │
│                            │   │  └ HybridCandidateSource     │
│ 复杂原生查询：@Query(native)│   │ Fusion(RRF) / Reranker 复用   │
└───────────┬───────────────┘   └──────────┬──────────────────┘
            │                               │
            ▼                               ▼
   JdbcClient / Spring Data JDBC 底层      原生 SQL（JdbcClient）
            └───────────────┬───────────────┘
                            ▼
                   PostgreSQL + pgvector
```

**两条数据访问通道并存**：
- **CRUD 通道**（Spring Data JDBC Repository）：简单表操作、消除重复查询、声明式。
- **检索通道**（原生 SQL + 查询层抽象）：向量/关键词/混合检索、复杂聚合，PG 特有语法无损。

## 4. 分阶段实施计划

> 原则：每阶段独立可交付、可回滚、有测试保护。不做大爆炸式重构。

### 阶段 0：收敛重复（最低风险，立即收益，零新依赖）

**不引入框架**，仅做提取与去重，为后续迁移铺路：

1. **抽取 `KbMembershipService`**（或 `KbMembershipQueries`）：合并 5 处 `requireKbMember`。各 Service 改为注入它。
2. **统一文档详情查询**：4 处 4-LATERAL SELECT 收敛为一个 `DocumentQueryService.findDetailBy(spec)`，WHERE 由参数决定（`byKb` / `byId` / `byOssKey`）。
3. **统一 ingest 状态变更**：`IngestTaskStatusUpdate` 值对象 + 单一 `updateStatus(docId, statusUpdate)` 方法，替换 4 处重复 UPDATE。
4. **统一事务策略**：删除孤立的 `@Transactional`，全部回归 `TransactionTemplate`；或反过来全部改 `@Transactional`（推荐后者更简洁，但需统一）。补 `@Transactional(readOnly=true)` 给只读查询。

**产出**：减少约 15-20 处重复 SQL，建立"查询边界"心智，零框架依赖。

### 阶段 1：引入 Spring Data JDBC，迁移 CRUD 表

加依赖：
```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-data-jdbc</artifactId>
</dependency>
```

为简单表建 Repository（这些表是典型 CRUD，无复杂检索）：
```java
@Table("users") public record UserEntity(...) {}
public interface UserRepository extends CrudRepository<UserEntity, Long> {
    Optional<UserEntity> findByUsername(String username);
}

@Table("knowledge_bases") public record KnowledgeBaseEntity(...) {}
public interface KnowledgeBaseRepository extends CrudRepository<KnowledgeBaseEntity, String> {}

@Table("kb_members") public record KbMemberEntity(...) {}
public interface KbMemberRepository extends CrudRepository<KbMemberEntity, Long> {
    long countByKbIdAndUserId(String kbId, Long userId);   // 替换 5 处重复 membership 检查
}
```

迁移顺序（按依赖简单度）：`users` → `refresh_tokens` → `knowledge_bases` → `kb_members` → `chat_sessions`/`chat_messages` → `qa_traces`。

**保留原生 SQL 的情况**（用 `@Query(nativeQuery=true)` 或继续 `JdbcClient`）：
- `documents` 的 4-LATERAL 详情查询
- `ingest_tasks` 带 quality_report JSONB 的状态查询
- 所有 outbox 的 CTE / `FOR UPDATE SKIP LOCKED`
- `chunk_children` / `chunk_parents` / `vector_store`（检索相关，归检索通道）

**JSONB 处理**：Spring Data JDBC 对 JSONB 需配 `Converter<String, MyJsonType>`（注册 `@ReadingConverter`/`@WritingConverter`），统一替代当前两套手写 JSON 序列化（`VectorIndexService.metadataJson` 字符串模板 + Jackson）。

### 阶段 2：引入检索查询层抽象

定义可组合的检索契约（与 `hybrid-search-design.md` 对齐）：

```java
// 检索请求：声明式、可组合
public record RetrievalRequest(
        String kbId,
        List<String> queries,          // 支持多查询
        int candidateLimit,
        Double maxVectorDistance,      // null = 不过滤
        boolean enableKeywordPath,
        Set<String> docTypeFilter,     // 可扩展的过滤维度
        Set<Integer> pageNumberFilter  // 未来：按页码/元数据过滤
) {}

// 候选源策略：每路召回一个实现，复杂 SQL 封装在内
public interface CandidateSource {
    List<RetrievedChunk> candidates(String kbId, String query, int limit);
}

@Component class VectorCandidateSource implements CandidateSource { ... }   // 现有向量 SQL
@Component class KeywordCandidateSource implements CandidateSource { ... }  // 混合检索新增
```

编排层（替代当前散在 `VectorSearchService` / 未来 `HybridSearchService` 的合并逻辑）：

```java
@Service
public class RetrievalOrchestrator {
    private final List<CandidateSource> sources;     // [vector, keyword]
    private final Fusion fusion;                      // RRF
    private final DocumentReranker reranker;

    public List<RetrievedChunk> retrieve(RetrievalRequest req) {
        Map<String, RetrievedChunk> merged = new LinkedHashMap<>();
        for (String q : req.queries()) {
            for (CandidateSource s : activeSources(req)) {
                int rank = 0;
                for (RetrievedChunk c : s.candidates(req.kbId(), q, req.candidateLimit())) {
                    rank++;
                    merged.merge(chunkKey(c), c.withRank(s.name(), rank), this::fuse);
                }
            }
        }
        return reranker.rerank(primary(req), rankedByRrf(merged), req.topK());
    }
}
```

**收益**：新增一路召回（如未来 BM25、元数据过滤路）只需实现 `CandidateSource`，编排与融合零改动。检索从"复制一整段 SQL"变成"注册一个策略"。

### 阶段 3（可选，按需）：检索引擎演进评估

仅在出现以下信号时启动：
- 数据量 > 千万级 chunk，HNSW 查询延迟劣化
- 评估证明 ts_rank 召回质量不足，需真 BM25
- 需要复杂聚合/分面（faceting）超出 PG 全文检索能力

此时评估 ParadeDB（同库 BM25，迁移面最小）或专用向量库。因阶段 2 已建立 `CandidateSource` 抽象，**替换引擎=替换一个策略实现**，编排层不变。

## 5. 关键设计决策记录

| 决策 | 选择 | 理由 |
|------|------|------|
| ORM 选型 | Spring Data JDBC，非 JPA | record 同构、无魔法、复杂 SQL 无损保留、迁移成本中 |
| 复杂检索 | 保留原生 SQL + 查询层抽象 | PG 特有语法任何 ORM 写不好；抽象解决"可组合性"而非"换引擎" |
| 外部检索引擎 | 暂不引入 | 无明确瓶颈，PG+pgvector+tsvector 已够；引擎演进留作阶段 3 |
| 迁移节奏 | 分阶段、可回滚 | 103 处 SQL 不能大爆炸；每阶段独立测试保护 |
| 事务策略 | 统一为单一机制 | 消除 `TransactionTemplate` + 孤立 `@Transactional` 的混用 |

## 6. 风险与缓解

| 风险 | 缓解 |
|------|------|
| Spring Data JDBC 对 JSONB/枚举映射需自定义 Converter | 集中注册一套 `Converters`，替换现有两套手写 JSON 逻辑，反而收敛 |
| 迁移期间两套数据访问并存增加认知负担 | 阶段化、每阶段收口一类表；README 标注"已迁移/未迁移"边界 |
| 测试直接断言原始 SQL/JSONB 列，迁移可能破坏 | 阶段 0 先补齐 Repository 层测试，迁移时测试先行保护 |
| 检索查询层抽象过度设计（YAGNI） | `CandidateSource` 接口极薄（单方法），且混合检索已证明第二路召回需求存在，非臆造 |
| JdbcClient 与 Spring Data JDBC 并存困惑 | 明确分工：CRUD→Repository，检索→JdbcClient/`@Query`，文档化边界 |

## 7. 推荐落地优先级

> 如果只做一件事：**阶段 0**（零依赖、立即消除重复、为一切铺路）。
> 如果做两件事：**阶段 0 + 阶段 2**（先消重复，再给检索可组合性——后者直接支撑混合检索落地）。
> 阶段 1（Spring Data JDBC 全量迁移）收益最大但工作量也最大，可在阶段 0、2 验证模式后逐步推进。

---

## 附录 A：本方案与混合检索方案的关系

`hybrid-search-design.md` 是"加功能"，本方案是"改结构"。二者顺序建议：

1. **先做本方案阶段 0**（消重复、建查询边界）——让 `KeywordSearchService` 有干净的接入点，而不是再加一份散落 SQL。
2. **再做本方案阶段 2**（`CandidateSource` 抽象）——`VectorCandidateSource` 封装现有向量 SQL，`KeywordCandidateSource` 作为第二个策略自然落地，混合检索的 `HybridSearchService` 退化为 `RetrievalOrchestrator` 的配置。
3. 阶段 1（Spring Data JDBC CRUD 迁移）与检索演进解耦，可独立并行。

即：**先建结构，再加功能**，避免混合检索堆叠在已有的散乱 SQL 之上。

## 附录 B：改动规模估算

| 阶段 | 新增/改造文件 | SQL 执行点变化 | 依赖变化 |
|------|-------------|---------------|---------|
| 阶段 0 | ~6 文件（抽取 service/query） | 103 → ~85（去重 15-20） | 无 |
| 阶段 1 | ~15 文件（Repository + Entity + Converter） | ~85 → ~50（CRUD 声明式，复杂 SQL 保留） | +spring-boot-starter-data-jdbc |
| 阶段 2 | ~6 文件（CandidateSource + Orchestrator + Fusion） | 检索 SQL 收敛进策略，执行点不变 | 无 |

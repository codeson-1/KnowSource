# 混合检索方案设计（向量 + 关键词，RRF 融合）

> Status: Draft（待评审）
> Owner: Retrieval
> 关联：`ADR-001-production-rag-path.md`、`KnowSource-RAG-架构设计文档.md`

## 1. 背景与现状

KnowSource 当前的生产检索路径是**纯向量检索**（见 `VectorSearchService.searchCandidates`）：

```
用户问题 → QueryRewrite（可选，多查询/压缩）→ embedQuery →
vector_store.embedding <=> queryEmbedding <= maxDistance(0.8) →
candidate-top-k(15) → DashScope reranker → topK(5)
```

核心事实（决定本方案形态）：

- 检索是**手写 SQL + `JdbcClient`**，**完全绕过 Spring AI 的 `VectorStore` 抽象**（`ADR-001`）。混合检索可在同一 SQL/Service 层平行扩展，无需与框架对抗。
- `vector_store` 表（`V1__vector_store.sql`）：`id, content(TEXT), metadata(JSONB), embedding(vector 1024), kb_id, doc_id, status, doc_version`。已有 HNSW 余弦索引。
- `content` 列即为 chunk 全文，`documents.title` 在检索 SQL 里已 JOIN 出来。这两者是关键词召回的两个可用文本源。
- **代码库内零关键词检索**：无 `tsvector / to_tsquery / ILIKE / BM25 / pg_trgm`。唯一的"词法"逻辑是 Java 侧的 `LexicalDocumentReranker`（token 重叠，rerank 兜底）和 `ChatService.hasLexicalEvidence`（Han bigram 相关性门禁），**它们都不是检索手段**。
- 现有融合模式已存在：`VectorSearchService.search(kbId, List<queries>, topK)` 用 `LinkedHashMap.merge` 按 `chunkKey` 去重、保留更高分，这是多路结果合并的现成范式。
- `RetrievedChunk` record：`...(distance, score)`，`score = 1 - distance`。
- 多查询重写产出的 `List<String>` retrieval queries 已是现成输入，关键词路可复用。

**纯向量的已知短板**（本方案要解决的）：

1. 专有名词 / 产品代号 / 编号 / 人名（如 `qwen3-rerank`、`ISO-27001`、`A1-B2`）在 embedding 空间里可能被"语义近但字面远"的 chunk 挤掉，字面精确匹配缺失。
2. 罕见词、低频实体在稠密向量里区分度不足。
3. 短查询（2-4 字）向量信号弱，但关键词信号强。

## 2. 技术选型与推荐

### 2.1 结论：**PG 原生 `tsvector` + GIN 索引**（simple 配置 + 应用层 bigram 分词），不引入第三方扩展。

### 2.2 三选一对比

| 方案 | 中文分词质量 | 安装复杂度 | 评分质量 | 是否需额外扩展 | 适配本项目 |
|------|------------|-----------|---------|--------------|-----------|
| **A. PG 原生 tsvector + GIN**（推荐） | 中（simple 配置按非字母切分，中文需应用层 bigram 补强） | **零**（PG 内置） | 中（ts_rank_cd） | 否 | ✅ |
| B. pg_trgm 三元组 + GIN | 中（按 3-gram，对中文可用但索引膨胀） | 低（contrib，多数 PG 镜像自带） | 弱（trigram 相似度，非词频） | 否 | △ |
| C. ParadeDB `pg_search`（真 BM25） | 中（依赖其 tokenizer） | **高**（需装第三方扩展，镜像/运维改造） | **强**（真 BM25，最接近 ES） | 是 | △ |

### 2.3 推荐理由（为什么选 A）

1. **零运维依赖**。本项目用 Docker `pgvector/pgvector:pg16` 镜像（`SPIKE.md` 记录）。方案 A 不需要换镜像、不需要装扩展，Flyway 一条迁移即可上线。方案 C 要换镜像或在容器里装 `pg_search`，运维面扩大；方案 B 虽是 contrib，但三元组对中文的索引体积会显著膨胀（每个汉字拆成多个 3-gram）。

2. **中文处理可控**。PG 内置全文检索默认按空白/标点切分，对无空格的中文句子几乎不分词（一整段当一个 token）。但本项目关键词召回的本质是**精确召回字面命中的 chunk**，不需要完美的语义分词。具体策略：
   - **写入侧**：用应用层 bigram 分词（与现有 `LexicalDocumentReranker` / `hasLexicalEvidence` 的 Han bigram 逻辑同源），把分词后的 token 用空格拼接，存入一个新增的 `content_tokens` 列，并对其建 `tsvector` 生成列 + GIN。
   - **查询侧**：用同一套 bigram 分词把用户问题切成 token，`plainto_tsquery('simple', ...)` 召回。
   - 这样"产品代号 qwen3-rerank"会被切成 `qwen3 rerank`（拉丁部分按 `simple` 自然切分，保留精确性），中文部分切成 bigram（如 `检索` → `检索`），兼顾**精确字面匹配**与**中文召回率**。

3. **与现有代码同构**。现有 `LexicalDocumentReranker`、`hasLexicalEvidence` 已经在做 Han bigram + Latin token 的提取（见 `evidenceTerms`）。把这套分词逻辑抽成共享工具，写入侧和查询侧复用，**不引入新的分词心智模型**。

4. **演进路径平滑**。tsvector + GIN 是 PG 全文检索的标准接口。将来若评估证明需要真 BM25，可在同一张 `content_tokens` 列上叠加 `pg_search` 的 BM25 索引（ParadeDB 支持在已有列上建 BM25 index），迁移面收敛在一处。即**方案 A 是方案 C 的前置子集，不阻塞未来升级**。

> **为什么不直接用 zhparser/pg_jieba？** 它们分词质量更好，但需要编译安装 PG 扩展（C 扩展），与 `pgvector` 镜像叠加有维护成本，且 bigram 在"关键词精确召回"这一目标下已够用。分词质量可在后续评估后再决定是否升级。

## 3. 总体架构

```
                              用户问题（经 QueryRewrite 产出的 List<queries>）
                                              │
                          ┌───────────────────┴───────────────────┐
                          ▼                                       ▼
                    向量路（现有）                              关键词路（新增）
              embedQuery → vector_store                   bigram 分词 → tsvector GIN
              cosine <= maxDistance                       ts_rank_cd 排序
              candidate-top-k=15                          candidate-top-k=15
                          │                                       │
                          └───────────────────┬───────────────────┘
                                              ▼
                                     RRF 倒数排名融合
                          score = Σ 1/(k + rank_i)，  k=60
                                              ▼
                                   去重（按 chunkKey）
                                              ▼
                              现有 DashScope reranker 精排
                                              ▼
                                         topK(5)
```

**关键设计原则**：

- 关键词路是向量路的**平行召回分支**，二者候选集合并后融合，**不替换**向量路。
- 融合采用 **RRF**（用户决策），对分数尺度不敏感，无需归一化调参。
- 融合后的候选仍交给**现有 reranker**精排，保留 `qwen3-rerank` 的语义判断，且当 reranker 不可用时自动退化到 `LexicalDocumentReranker`（现有降级链不变）。
- 整条链路通过配置开关控制，默认可关闭，保证灰度安全。

## 4. 数据库改动

### 4.1 新增 Flyway 迁移 `V8__hybrid_search_fts.sql`

> 因为是开发阶段、数据可清，迁移**只建结构**，存量数据由重新入库（reindex）自然填充 `content_tokens`，无需写回填 UPDATE。

```sql
-- 混合检索：为 vector_store 增加关键词全文检索能力
-- 思路：content_tokens 存"应用层 bigram 分词后的空格连接串"，
--       tsv 是基于 content_tokens 的 simple 配置 tsvector 生成列，GIN 索引加速召回。

ALTER TABLE vector_store ADD COLUMN IF NOT EXISTS content_tokens TEXT;
ALTER TABLE vector_store ADD COLUMN IF NOT EXISTS doc_title VARCHAR(256);

-- generated tsvector：随 content_tokens 自动更新，无需写入侧维护
ALTER TABLE vector_store
    ADD COLUMN IF NOT EXISTS content_tsv tsvector
    GENERATED ALWAYS AS (to_tsvector('simple', coalesce(content_tokens, ''))) STORED;

-- 关键词召回主索引
CREATE INDEX IF NOT EXISTS idx_vector_store_content_tsv_gin
    ON vector_store USING gin (content_tsv);

-- 限定知识库范围时缩小扫描面（与现有 idx_vector_kb_status_doc_ver 配合）
CREATE INDEX IF NOT EXISTS idx_vector_store_kb_status_tsv_gin
    ON vector_store USING gin (content_tsv)
    WHERE status = 'published';
```

说明：
- `content_tokens`：由 `VectorIndexService` 在写入 chunk 时填充（bigram 分词结果，空格连接）。**generated 列 `content_tsv` 依赖它，因此写入侧只需填 `content_tokens`，tsv 自动派生**——这点对"零代码改动写入路径"是关键。
- `doc_title`：冗余存 `documents.title`，供关键词召回时给标题命中加权（可选优化，见 §6.3）。若不标题加权可省略此列。
- 不在迁移里用 `simple` 配置直接 `to_tsvector(content)`：因为未分词的中文原文在 simple 下几乎不分词，必须先经应用层 bigram。

### 4.2 写入侧改动（`VectorIndexService.doReindex`）

在现有 INSERT 增加两列：

```java
// 现状
INSERT INTO vector_store (id, content, metadata, embedding, kb_id, doc_id, status, doc_version)
VALUES (...)

// 改为
INSERT INTO vector_store (id, content, content_tokens, doc_title, metadata, embedding, kb_id, doc_id, status, doc_version)
VALUES (..., :contentTokens, :docTitle, ...)
```

`content_tokens` 由新增的共享分词工具生成（见 §5.2），`doc_title` 从 `documents` 查出（`doReindex` 入参当前没有 title，需顺带传入或补一次查询）。

## 5. 代码改动点

### 5.1 改动清单

| 文件 | 改动类型 | 说明 |
|------|---------|------|
| `V8__hybrid_search_fts.sql`（新） | 新增 | tsvector 生成列 + GIN 索引（见 §4.1） |
| `index/TextTokenizer.java`（新） | 新增 | 共享分词工具：Han bigram + Latin token，提取自现有 `LexicalDocumentReranker`/`hasLexicalEvidence` 逻辑 |
| `index/VectorIndexService.java` | 改造 | INSERT 增加 `content_tokens` / `doc_title`；`doReindex` 入参补 title |
| `chat/RetrievedChunk.java` | 改造 | 增加 `source`（`vector`/`keyword`/`hybrid`）、可选 `keywordRank`/`vectorRank` 字段，用于 RRF 与 trace |
| `chat/KeywordSearchService.java`（新） | 新增 | 关键词召回：bigram 分词查询 → `plainto_tsquery` → `ts_rank_cd` 排序 |
| `chat/HybridSearchService.java`（新） | 新增 | 编排两路召回 + RRF 融合；`@Primary` 替换 `VectorSearchService` 在 `ChatService` 中的角色，或由 `ChatService` 直接持有 |
| `chat/VectorSearchService.java` | 改造 | `searchCandidates` 改为可被 `HybridSearchService` 复用的内部召回（返回带 rank 的候选）；或抽取 `VectorCandidateSearcher` |
| `chat/ReciprocalRankFusion.java`（新） | 新增 | 纯函数：`fuse(List<List<RetrievedChunk>>, int k) → List<RetrievedChunk>`，便于单元测试 |
| `chat/ChatService.java` | 改造 | `prepareContext` 中把 `vectorSearchService.search(...)` 换成 `hybridSearchService.search(...)`；`retrieved_chunks` trace 记录两路分数 |
| `application.yml` | 改造 | 新增 `knowsource.retrieval.hybrid.*` 配置（见 §7） |

### 5.2 共享分词工具 `TextTokenizer`

把现有散落两处的 Han bigram + Latin token 逻辑收敛为一个工具类，写入侧与查询侧复用，保证**索引与查询分词一致**（全文检索最易踩的坑就是两边分词不一致导致召回为零）。

```java
public final class TextTokenizer {
    /** 拉丁 token：字母数字序列（保留 qwen3、iso-27001 这类代号）；中文：2-gram bigram */
    public static List<String> tokenize(String text) { ... }
    /** 索引/查询用：空格连接，喂给 to_tsvector/plainto_tsquery('simple', ...) */
    public static String joinForTsv(String text) {
        return String.join(" ", tokenize(text));
    }
}
```

- 写入：`content_tokens = TextTokenizer.joinForTsv(chunk.content())`
- 查询：`plainto_tsquery('simple', TextTokenizer.joinForTsv(query))`

### 5.3 关键词召回 SQL 草案（`KeywordSearchService`）

与向量路共享相同的 `WHERE` 过滤（`kb_id` / `status` / 文档生命周期 / `(doc_id,doc_version)` JOIN），保证 ACL 与版本一致性不受影响：

```sql
SELECT
    vs.metadata ->> 'chunkId' AS chunk_id,
    vs.doc_id,
    vs.doc_version,
    d.title,
    vs.content,
    vs.metadata ->> 'parentChunkId' AS parent_chunk_id,
    COALESCE((vs.metadata ->> 'chunkIndex')::int, 0) AS chunk_index,
    c.page_number,
    COALESCE(vs.metadata ->> 'chunkType', c.chunk_type) AS chunk_type,
    ts_rank_cd(0, vs.content_tsv, :tsq) AS rank
FROM vector_store vs
JOIN documents d
  ON d.id = vs.doc_id AND d.version = vs.doc_version AND d.kb_id = vs.kb_id
LEFT JOIN chunk_children c
  ON c.id = vs.metadata ->> 'chunkId'
 AND c.doc_id = vs.doc_id AND c.doc_version = vs.doc_version
WHERE vs.kb_id = :kbId
  AND vs.status = 'published'
  AND d.status = 'PUBLISHED'
  AND d.index_status = 'SYNCED'
  AND vs.content_tsv @@ :tsq            -- 关键词命中
ORDER BY ts_rank_cd(0, vs.content_tsv, :tsq) DESC
LIMIT :topK
```

`:tsq` = `plainto_tsquery('simple', <bigram 分词后的查询串>)`。`ts_rank_cd` 用 cover density 排序，比 `ts_rank` 更关注词项邻近度。

### 5.4 RetrievedChunk 扩展

```java
record RetrievedChunk(
        String chunkId, String docId, int docVersion, String title,
        String content, String parentChunkId, int chunkIndex,
        Integer pageNumber, String chunkType,
        double distance, double score,
        // 新增（可选，默认值保证向后兼容）
        String source,        // "vector" | "keyword" | "hybrid"
        Integer vectorRank,   // 该 chunk 在向量路的排名（1-based），融合用
        Integer keywordRank,  // 该 chunk 在关键词路的排名（1-based），融合用
        double rrfScore       // RRF 融合分
) { }
```

向后兼容：构造向量路/关键词路候选时分别只填各自的 rank，融合后填 `rrfScore` 与 `source="hybrid"`。

## 6. RRF 融合算法

### 6.1 公式

对每个候选 chunk，其 RRF 分数为各路排名的倒数之和：

```
RRF(d) = Σ_{路 i 中存在 d}  1 / (k + rank_i(d))
```

- `k = 60`（业界经验值，源自 Cormack 等人 2009 论文，平滑高位排名的影响）。
- `rank` 从 1 开始；未出现在某路的 chunk 该路贡献为 0。
- **同 chunk 在两路都命中**：得分叠加，天然提升"双路共识"的 chunk——这正是混合检索的价值。

### 6.2 与多查询（multi-query）的交互

现状 `search(kbId, List<queries>, topK)` 对每个 query 各跑一次向量召回再合并。混合检索下，每个 query 内部都要做"向量+关键词"两路。两层叠加：

```
for q in queries:                      # 多查询层
    vecCands  = vectorSearch(q)        #   向量路
    kwCands   = keywordSearch(q)       #   关键词路
    perQueryMerged = RRF([vecCands, kwCands])   # 单 query 内两路融合
    全部 query 的 perQueryMerged 再合并（现有 LinkedHashMap.merge 范式，保留更高 rrfScore）
→ reranker → topK
```

> 简化选项（建议初版采用）：**只对主 query 做关键词路**，多查询的其余分支只走向量路。理由：多查询本就是为扩大向量语义覆盖，关键词路对主 query 的字面召回最有价值；这样避免关键词召回被 LLM 改写出的非自然语句污染。配置项 `hybrid.keyword-on-rewrites` 控制是否对全部 rewrite 都跑关键词路。

### 6.3 可选：标题加权

若启用 `doc_title` 列，可在关键词路用 `ts_rank_cd` 对 title 也算一份并按权重叠加（如 `0.7*content_rank + 0.3*title_rank`），让"标题直接命中"的文档优先。属增强项，初版可不实现。

## 7. 配置项设计

沿用现有 `knowsource.retrieval.*` 命名风格（`application.yml`）：

```yaml
knowsource:
  retrieval:
    max-distance: 0.8              # 现有
    candidate-top-k: 15            # 现有
    hybrid:                        # 新增
      enabled: ${KNOWSOURCE_HYBRID_ENABLED:true}            # 总开关；false 时完全退回纯向量
      keyword-candidate-top-k: ${KNOWSOURCE_HYBRID_KW_TOP_K:15}
      rrf-k: ${KNOWSOURCE_HYBRID_RRF_K:60}                  # RRF 平滑常数
      keyword-on-rewrites: ${KNOWSOURCE_HYBRID_KW_ON_REWRITES:false}  # 是否对多查询分支都跑关键词路
      min-keyword-tokens: ${KNOWSOURCE_HYBRID_KW_MIN_TOKENS:1}        # 分词后 token 少于此数则跳过关键词路（避免空召回）
```

降级策略（`HybridSearchService` 内部）：
- `hybrid.enabled=false` → 纯向量，行为与现状完全一致。
- 关键词路抛错 / 返回空 / token 不足 → 只用向量路结果，不阻断主流程。
- 向量路抛错（现状已是 `return List.of()`）→ 只用关键词路结果。

## 8. 与现有机制的交互

| 现有机制 | 交互方式 |
|---------|---------|
| **QueryRewrite**（压缩/多查询） | 关键词路输入即 rewrite 后的 `List<String>`；§6.2 决定是否对全部分支跑关键词 |
| **DashScope reranker**（`qwen3-rerank`） | RRF 融合后的候选集仍进 reranker 精排，顺序不变；reranker 失败仍降级到 `LexicalDocumentReranker` |
| **`hasLexicalEvidence` 门禁** | 保持不变。它是"防幻觉兜底"，与召回方式无关；混合检索反而让相关性证据更强，门禁通过率提升 |
| **`qa_traces.retrieved_chunks`(JSONB)** | 扩展记录每个 chunk 的 `source`/`vectorRank`/`keywordRank`/`rrfScore`，便于离线分析两路贡献 |
| **ACL / 版本一致性** | 关键词路 SQL 复用与向量路**完全相同**的 `kb_id`/`status`/`index_status`/`(doc_id,doc_version)` 过滤，安全边界不变 |
| **`VectorIndexService` outbox 入库** | 写入侧增加 `content_tokens` 填充，generated tsv 自动派生，入库流程不变 |

## 9. 测试策略

### 9.1 单元测试（纯 Java，无 PG）

- **`ReciprocalRankFusionTest`**：构造两路固定排名的候选，断言 RRF 分数与排序。覆盖：单路独占、双路共识、k 值影响、空列表、并列排名。
- **`TextTokenizerTest`**：断言中文 bigram、拉丁代号（`qwen3-rerank`→`qwen3 rerank`）、混合文本的分词结果；保证写入/查询两侧一致。
- **`HybridSearchServiceTest`**（mock 两路 service）：断言降级链（关开关、关键词路空、关键词路抛错、向量路空）。

### 9.2 集成测试（Testcontainers PG，沿用现有 `application-db.yml` 模式）

- 用 `pgvector/pgvector:pg16` 容器，跑真实 tsvector + GIN。
- 种入若干中英文 chunk（含代号、罕见词），断言：
  - 纯代号查询（如 `qwen3-rerank`）关键词路能召回，纯向量可能漏。
  - 语义查询（如"如何保证检索安全"）两路都能召回，RRF 后双路共识 chunk 排前。
  - `hybrid.enabled=false` 时结果与纯向量一致（回归保护）。

### 9.3 A/B 评估（复用 `docs/eval`）

- 用现有评估集对"纯向量"vs"混合检索"跑离线指标：召回率@K、MRR、最终答案相关性（LLM-as-judge）。
- 重点观察：代号/编号类查询的提升、语义类查询是否退化（RRF 权重是否压制了向量强项）。

## 10. 风险与后续演进

| 风险 | 缓解 / 演进 |
|------|-----------|
| **bigram 分词中文召回精度一般**（"检索"与"检查"共享 gram 产生噪声） | 短期靠 `ts_rank_cd` + reranker 抑制噪声；中期评估后可升级到 `zhparser`/`pg_jieba` 词级分词，仅改 `TextTokenizer` 与迁移的 tsv 配置 |
| **真 BM25 评分缺失**（ts_rank 非 BM25，词频/文档频率建模弱） | 方案 A 是方案 C（ParadeDB `pg_search`）的前置子集；`content_tokens` 列可直接叠加 BM25 索引，迁移面收敛在一处 |
| **写入路径新增列的迁移成本** | 开发阶段数据可清，reindex 自然回填；生产化时迁移脚本加一次性 `UPDATE vector_store SET content_tokens = ...`（需 Java 分词，或接受重新入库） |
| **关键词路与向量路候选不重叠时 RRF 退化为简单并集** | 属预期行为；reranker 兜底精排。可通过 `min-keyword-tokens` 等门槛减少无意义关键词召回 |
| **多查询 + 关键词路组合的延迟增加** | 初版只对主 query 跑关键词路（§6.2）；关键词路是 GIN 索引扫描，单次延迟通常 < 向量 ANN |

---

## 附录 A：改动文件树速览

```
src/main/resources/db/migration/V8__hybrid_search_fts.sql   [新]
src/main/java/com/knowsource/
  index/TextTokenizer.java                                  [新]
  index/VectorIndexService.java                             [改: INSERT 加列]
  chat/RetrievedChunk.java                                  [改: 加 source/rank/rrfScore]
  chat/KeywordSearchService.java                            [新]
  chat/HybridSearchService.java                             [新]
  chat/ReciprocalRankFusion.java                            [新]
  chat/VectorSearchService.java                             [改: 抽取可复用召回]
  chat/ChatService.java                                     [改: 调用 HybridSearchService, trace]
src/main/resources/application.yml                          [改: hybrid.* 配置]
src/test/java/com/knowsource/chat/
  ReciprocalRankFusionTest.java                             [新]
  TextTokenizerTest.java                                    [新]
  KeywordSearchServiceIT.java                               [新, Testcontainers]
  HybridSearchServiceTest.java                              [新]
```

## 附录 B：实施建议顺序

1. `V8` 迁移 + `TextTokenizer` + 写入侧填 `content_tokens` → 重新入库，肉眼校验 tsv。
2. `KeywordSearchService` + 集成测试，单独验证关键词召回质量。
3. `ReciprocalRankFusion` + 单测。
4. `HybridSearchService` 接入两路 + reranker，`ChatService` 切换，trace 记录。
5. A/B 评估，调 `rrf-k` 与开关。

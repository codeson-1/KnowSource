# KnowSource 企业智能知识库问答系统（MVP）代码 Review 报告

| 属性 | 内容 |
|------|------|
| 文档版本 | V1.3 |
| 评审对象 | KnowSource MVP 版本源码 |
| 评审依据 | `docs/architecture/KnowSource-RAG-架构设计文档.md` |
| 评审日期 | 2026-06-25 |
| 评审范围 | 架构合规性、功能完整性、性能可用性、安全合规、代码质量 |
| P0 修复日期 | 2026-06-26 |
| P0 修复状态 | **全部 4 项已修复（86 个测试通过）** |
| MVP P1 收尾日期 | 2026-06-26 |
| MVP P1 收尾状态 | **B-2 / B-3 / E-1 / E-2 已按 MVP 范围修复（全量测试 88 个通过）** |
| P2 安全收口日期 | 2026-06-26 |
| P2 安全收口状态 | **D-3 / D-4 已修复，待回归验证** |

> **修复说明（V1.3）**：V1.1 已记录 4 个 P0 问题（A-1、A-2、B-1、D-1）的修复落地情况；V1.2 按 MVP 收尾范围修复 B-2、B-3、E-1/E-2；V1.3 顺手修复 D-3/D-4 两个低成本安全 P2。修复细节见文末附录。

---

## 目录

- [一、整体评估](#一整体评估)
- [二、问题清单（按维度分类）](#二问题清单按维度分类)
  - [A. 架构合规性](#a-架构合规性)
  - [B. 功能完整性](#b-功能完整性)
  - [C. 性能与可用性](#c-性能与可用性)
  - [D. 安全合规](#d-安全合规)
  - [E. 代码质量与可维护性](#e-代码质量与可维护性)
- [三、优化建议](#三优化建议)
- [四、验证建议（核心链路测试用例）](#四验证建议核心链路测试用例)
- [五、值得肯定的最佳实践](#五值得肯定的最佳实践设计未明确但实现优秀)
- [六、总结](#六总结)
- [附录：P0 修复记录](#附录p0-修复记录)
- [附录：MVP P1 收尾修复记录](#附录mvp-p1-收尾修复记录)
- [附录：P2 安全收口修复记录](#附录p2-安全收口修复记录)

### 优先级定义

| 优先级 | 含义 |
|--------|------|
| **P0** | 违反架构核心约定 / 阻断功能运行 / 存在严重安全漏洞 |
| **P1** | 影响 MVP 关键指标（如性能 / 拒答率）/ 存在潜在安全风险 |
| **P2** | 不影响核心功能，但降低可维护性 / 不符合编码规范 |

---

## 一、整体评估

### 1. 符合度总结

| 维度 | 评估 | 说明 |
|------|------|------|
| **架构核心原则符合率** | **约 70%** | 延迟向量化、Outbox、SQL-first、kbId 服务端绑定、检索 Filter 落地扎实；但**未采用 Spring AI Advisor/RetrievalAugmentationAdvisor 链**，RAG 流水线被手写实现替换 |
| **核心功能完成度** | **约 75%** | 文档生命周期、Outbox 消费、检索、QaTrace、RBAC、评测齐全；**无 API Key 时已显式 503/50002，真实生成需配置模型 Key** |
| **可观测性** | **约 30%** | **已集成 Actuator/Micrometer/Prometheus**，关键链路指标与 QaTrace 形成互补 |
| **测试覆盖** | **优秀（约 85%）** | 核心链路（发布/检索/拒答/多轮/SSE/重试/越权）均有集成测试，质量高 |

### 2. 三大关键风险点（速览）

1. **[P0] ✅ RAG 架构偏离设计**：完全未用 `RetrievalAugmentationAdvisor`/`Advisor` 链，自己写了「检索→重排→生成」手写流水线。设计文档 §7.7 是核心叙事点，面试深挖时「为什么没用 Spring AI 的 RAG 模块」会难以自圆其说。**（已通过 ADR-001 补记决策与代价修复，见 A-1）**
2. **[P0] ✅ 可观测性几乎为零**：无 Actuator、无 Micrometer、无 `/actuator/metrics`、无 `/actuator/prometheus`。设计 §11.2 的 8 项指标（latency/首 token/检索耗时）只落到了 QaTrace 表，没有时序指标。**（已集成 Actuator + Micrometer + Prometheus 并埋点修复，见 A-2）**
3. **[P1] 答案生成降级语义模糊**：未配置 `AI_DASHSCOPE_API_KEY` 时，系统**静默返回「已检索到相关知识片段，下一阶段将接入大模型生成最终回答」占位文本**，且测试将其作为期望值固化。这与「LLM 生成答案 + 引用」的核心功能不符。**（核心主链路部分已随 B-1 修复：无 Key 时显式报错而非静默占位）**

### 3. 架构约定落地情况速览

| 架构原则 | 落地情况 | 结论 |
|----------|----------|------|
| 分层解耦（Controller→Service→AI） | Controller 不直接调 ChatModel/VectorStore | ✅ 符合 |
| 延迟向量化（草稿不写向量） | 上传仅写 chunk_children，发布才 Outbox 触发 | ✅ 符合 |
| Transactional Outbox | `document_publish_events` + `FOR UPDATE SKIP LOCKED` + 重试字段完整 | ✅ 符合 |
| SQL-first（JdbcClient + Flyway） | 全链路 JdbcClient 手写 SQL，V1~V4 迁移完整 | ✅ 符合 |
| RAG auto 路由（单轮 Naive/多轮 Modular） | `RagProfileRouter` 实现，MultiQuery=2 | ✅ 符合（但手写非 Advisor） |
| 检索 Filter（kbId 服务端绑定 + doc_id+doc_version） | kbId 取自 `@PathVariable`，JOIN 精确匹配 | ✅ 符合 |
| `KnowSourceVectorStore extends PgVectorStore` | 改为 `VectorIndexService` + `VectorSearchService` 裸 SQL | ❌ 偏离 |
| Spring AI Advisor 链 / Modular RAG | 自写检索+重排+生成流水线 | ❌ 偏离 |
| OverlapTokenSplitter（token 级重叠） | `SimpleTextChunker` 字符切分，零 overlap | ❌ 偏离 |
| Actuator + Micrometer 可观测性 | 已集成 Actuator、Micrometer、Prometheus 与关键 Timer | ✅ 符合 |

---

## 二、问题清单（按维度分类）

### A. 架构合规性

#### 🔴 A-1 [P0] ✅ RAG 核心未采用 Spring AI Advisor 链，与设计文档严重偏离

- **位置**：`ChatService.java:109-137`（`prepareContext`）、`VectorSearchService.java`、`QueryRewriteService.java`、`SpringAiAnswerGenerator.java`
- **问题描述**：设计文档 §7.7 明确要求 `RetrievalAugmentationAdvisor` + `QueryTransformer` + `VectorStoreDocumentRetriever` + `DocumentPostProcessor` + `ContextualQueryAugmenter` 组装 Modular RAG。实际代码完全自研：`ChatService` 调 `VectorSearchService.search()` → `QueryRewriteService.rewrite()` → `DashScopeDocumentReranker.rerank()` → 拼 prompt 调 `ChatClient`。Spring AI 的 `spring-ai-rag` 依赖**甚至未在 pom.xml 中引入**。
- **影响**：
  1. 失去 Spring AI RAG 模块的「接口可替换」卖点（设计 §5.1 选型理由）；
  2. `allowEmptyContext=false` 这个**设计反复强调的拒答机制**未通过 `ContextualQueryAugmenter` 落地，而是用 `hasLexicalEvidence()` 词项匹配 + 占位 fallback 替代，拒答逻辑脆弱；
  3. 面试叙事与代码不符，深挖易翻车。
- **修复建议**：若时间允许，将 `prepareContext` 重构为通过 `RetrievalAugmentationAdvisor` 组装（见设计 §7.7 `RagAdvisorFactory` 伪代码）。若 MVP 已来不及，**至少在 ADR 或 README 中明确记录「MVP 用手写流水线替代 Advisor 链的原因与代价」**，把代码与文档对齐，避免「文档说一套代码做一套」。

#### 🔴 A-2 [P0] ✅ 可观测性缺失：无 Actuator / Micrometer

- **位置**：`pom.xml`（无 `spring-boot-starter-actuator` / `micrometer-registry-prometheus`）、全局无 `MeterRegistry` 注入
- **问题描述**：设计 §11.2 要求暴露 `knowsource.rag.retrieval`/`generation_first_token`/`ingest.index.success` 等 8 项指标到 `/actuator/metrics` 和 `/actuator/prometheus`。代码中检索 `micrometer|actuator|MeterRegistry` **零命中**。
- **影响**：
  1. 无法监控「单轮 P95<5s」「首 token<2.5s」「发布同步 P95<60s」等 KSI（设计 §2.5）；
  2. QaTrace 只能事后查，无法实时告警；
  3. 运维盲区。
- **修复建议**：

  ```xml
  <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-actuator</artifactId>
  </dependency>
  <dependency>
      <groupId>io.micrometer</groupId>
      <artifactId>micrometer-registry-prometheus</artifactId>
  </dependency>
  ```

  并在 `application.yml` 配置：

  ```yaml
  management:
    endpoints:
      web:
        exposure:
          include: health,info,prometheus,metrics
  ```

  在 `ChatService.streamContext` / `VectorSearchService.search` / `VectorIndexService.reindexPublishedDocument` 关键埋点处注入 `MeterRegistry` 记录 Timer。

#### 🟡 A-3 [P1] `KnowSourceVectorStore extends PgVectorStore` 未实现，改用裸 JdbcClient

- **位置**：`VectorIndexService.java:57-79`（直接 `INSERT INTO vector_store`）、`VectorSearchService.java:95-129`（手写检索 SQL）
- **问题描述**：设计 §6.4 / ADR-001 **强制要求**自定义 `KnowSourceVectorStore` 重写 `doAdd()` / `doSearch()`，理由是「升级 Spring AI 时 review 两处重写」。实际改为两处独立 Service 各自手写 SQL，向量读写逻辑**分散在 `VectorIndexService` 和 `VectorSearchService` 两个类**，反而比集中在一个 Store 更难维护。
- **评估**：功能上**等价且正确**（冗余列 `kb_id/doc_id/status/doc_version` 都写了，B-tree 索引也建了），是一处合理的工程简化，但**与 ADR 文字不符**。
- **修复建议**：要么落地 `KnowSourceVectorStore` 集中读写逻辑，要么修订 ADR-001 说明「MVP 用 JdbcClient 直写替代 PgVectorStore 子类」并记录代价。

#### 🟡 A-4 [P1] OverlapTokenSplitter 未实现，切块无 overlap

- **位置**：`SimpleTextChunker.java`（字符级语义切分，parent=1200 字符 / child=400 字符，**无 overlap**）
- **问题描述**：设计 §7.2.2 **强烈强调**自研 `OverlapTokenSplitter` 实现 token 级滑动窗口重叠（parent overlap=200、child overlap=80），并明确指出「无 overlap 会因语义断裂导致召回质量下降」「W5 golden set 对比 Recall@5 应差 ≥3 个百分点」。实际 `SimpleTextChunker` 是纯字符切分、零 overlap。
- **影响**：
  1. 召回质量上限被压低；
  2. 设计的 W5 校验机制无法执行（无对照组）；
  3. 面试问「你的 overlap 怎么实现的」时无代码支撑。
- **修复建议**：在 `SimpleTextChunker.splitRanges` 中引入 overlap 参数，相邻 chunk 末尾/开头共享 `overlapSize` 字符；或按设计落地 `OverlapTokenSplitter`。

#### 🟢 A-5 [P2] TokenTextSplitter / TikaDocumentReader 抽象未落地

- **位置**：`PlainTextDocumentTextExtractor.java`（PDF 用 PDFBox、Word 用 Tika `AutoDetectParser`）
- **问题描述**：设计 §7.2.1 要求用 Spring AI `TikaDocumentReader` 统一抽象。实际直接用底层 Tika + PDFBox，未走 `Document` 抽象。
- **评估**：功能可用，但失去了 Spring AI 文档解析的统一抽象与 `Document.metadata` 传递。

---

### B. 功能完整性

#### 🔴 B-1 [P0] ✅ LLM 答案生成降级为占位文本，核心问答功能未闭环

- **位置**：`SpringAiAnswerGenerator.java:13`（`@ConditionalOnBean(ChatClient.Builder.class)`）、`ChatService.java:80-92`（`answerGenerator == null ? context.fallbackAnswer() : ...`）
- **问题描述**：当 `AI_DASHSCOPE_API_KEY` 为空（`application.yml` 默认值即为空），`ChatClient.Builder` Bean 不创建，`SpringAiAnswerGenerator` 不注册，于是 `answerGeneratorProvider.getIfAvailable()` 返回 null，答案回退到 `draftAnswer()`（「已检索到相关知识片段，下一阶段将接入大模型生成最终回答。参考来源：[1]」）。**测试 `ChatControllerTest` 把这个占位文本当期望值断言**（如 line 104 `startsWith("已检索到相关知识片段")`），说明当前主链路根本没跑通真实 LLM。
- **影响**：
  1. F-06「流式问答」核心功能未真正实现；
  2. 设计 §3.1 US-03「带引用的答案」不成立；
  3. demo 时若忘配 Key，演示效果是「检索到片段但不回答」。
- **修复建议**：
  1. 至少在 `application-local.yml` 预置一个测试 Key 或 mock；
  2. 区分「无 Key」与「Key 失效」的提示，无 Key 时应**显式报错**（返回 50002）而非静默占位；
  3. 评测 `EvalRunnerService` 应强制要求 LLM 可用，否则评测无意义。

#### 🟡 B-2 [P1] ✅ 库外拒答依赖 `hasLexicalEvidence` 词项匹配，非 `allowEmptyContext=false`

- **位置**：`ChatService.java:123`（`chunks.isEmpty() || !hasLexicalEvidence(...)` → 拒答）、`ChatService.java:265-316`
- **问题描述**：设计 §7.5.1 要求四层防护，其中「空上下文拒答」由 `ContextualQueryAugmenter.allowEmptyContext(false)` 实现。实际用自写的 `hasLexicalEvidence()`：对 query 做 Latin/汉字 bigram 分词，检查召回 chunk 是否含任一词项。这是**词法重叠判断，不是语义拒答**。
- **风险**：
  1. 用户问「年假多少天」，若召回的 chunk 含「年假」「多少」「天」词项则放行，但 LLM 实际可能编造具体数字；
  2. 中文 bigram（如「年假」→「年假」二字组合）对短查询敏感度低，可能误拒；
  3. 设计反复强调的 `allowEmptyContext` 契约未落地。
- **修复建议**：若坚持手写流水线，应改为「向量 distance 超阈值即拒答」+「LLM prompt 内置拒答规则」+「response 层校验 sources 非空」三层，弱化词项 gate。长期回到 `ContextualQueryAugmenter`。
- **MVP 收尾修复（2026-06-26）**：保留 `VectorSearchService` 的 `max-distance` 向量阈值，同时将 `hasLexicalEvidence` 从「任意词项命中」收紧为「过滤泛化/弱证据词后仍有强证据词命中」。新增 `refusesChineseQuestionWhenOnlyWeakGenericTermsOverlap` 覆盖“只有审批/流程/制度等泛词重叠”的误召回场景，避免库外问题因泛词桥接被放行。

#### 🟡 B-3 [P1] ✅ `token_version` 递增但 JWT 校验从未检查它

- **位置**：`AuthService.java:177-183`（`updateUserRole` 递增 `token_version`）、`JwtService.java:38-44`（payload 无 `tv` 字段）、`JwtService.parseAccessToken`（无 token_version 校验）
- **问题描述**：设计 §8.6.2 要求「改密/禁用时递增 `token_version` 并拒绝旧 JWT（payload 含 `tv`）」。代码确实在改角色时递增了 `token_version`，但：
  1. JWT payload 根本不含 `tv`；
  2. `parseAccessToken` 不查库比对；
  3. 没有改密接口。

  **递增的 token_version 是死字段，旧 JWT 永远有效**。
- **影响**：禁用/降级用户后，其已有 accessToken 仍可用到自然过期（15min）。虽窗口短，但违背设计承诺。
- **修复建议**：`createAccessToken` 加 `"tv": user.tokenVersion()`；`JwtAuthenticationFilter` 解析后查库校验 `payload.tv == users.token_version`（可加短缓存）。
- **MVP 收尾修复（2026-06-26）**：`CurrentUser` 携带 `tokenVersion`；登录/注册/刷新签发的 JWT payload 写入 `tv`；`JwtAuthenticationFilter` 解析 token 后按 username 查库，比对 `uid/global_role/token_version`，旧版本 token 直接视为无效认证。新增 `roleChangeInvalidatesExistingAccessToken`，验证管理员改角色后旧 accessToken 返回 401，重新登录后新 token 可用。

#### 🟢 B-4 [P2] `ingestExecutor` 无僵死回收

- **位置**：`DocumentService.markIngestParsing` 后无超时扫描
- **问题描述**：设计 §7.3.1 要求「`PARSING` 超过 30min → FAILED；启动时扫描恢复」。代码只有索引 Outbox 的僵死回收（`recoverStaleSyncingEvents`），**解析任务无僵死回收**。若 Tika 解析卡死或进程崩溃，`ingest_tasks` 会永久停在 PARSING。
- **修复建议**：仿照 `DocumentIndexEventPoller` 增加解析任务僵死扫描（`started_at < now()-30min AND status='PARSING'` → FAILED）。

#### 🟢 B-5 [P2] 缺少 Knife4j（API 文档）

- **位置**：`pom.xml` 无 knife4j 依赖
- **问题描述**：设计 §4.3 / §9.1 要求 Knife4j（`http://localhost:8080/doc.html`），实际未引入。Demo 与面试展示缺一环。

---

### C. 性能与可用性

#### 🟡 C-1 [P1] 检索 SQL 每次 JOIN documents，未用 IndexQueryService 缓存

- **位置**：`VectorSearchService.searchCandidates:107-122`
- **问题描述**：设计 §7.4.2 要求用 `IndexQueryService`（Caffeine 缓存 30s）预先查出 `kb` 下 `index_status=SYNCED` 的 `(docId, version)` 列表，再注入 Filter。实际 SQL 用 `JOIN documents d ON ... AND d.index_status='SYNCED'` 实时 JOIN。功能等价，但：
  1. 每次检索多一次 JOIN；
  2. 设计反复强调的「服务端绑定 syncedDocVersions」防线未独立实现，而是隐含在 JOIN 里。
- **评估**：当前数据量下 JOIN 性能可接受（有 `idx_documents_kb_status_index` 索引），但偏离设计且失去缓存优化。

#### 🟡 C-2 [P1] SSE 首个 `token` 事件实际是整段占位文本，非流式

- **位置**：`ChatService.sendToken:194-204`、`SpringAiAnswerGenerator.stream:43-60`
- **问题描述**：当 `streamingAnswerGenerator == null`（无 Key）时，`sendToken(emitter, answer, context.fallbackAnswer(), ...)` 一次性把整段 fallback 当一个 token 发出，**伪流式**。即使有 Key，`blockLast()` 在 `chatExecutor` 线程内阻塞等待流结束，首 token 延迟取决于 LLM。
- **影响**：设计 §2.5「首 token < 2.5s」在无 Key 场景无法度量。

#### 🟢 C-3 [P2] `indexExecutor` 配置 core=1/max=2，与设计 core=2/max=4 不符

- **位置**：`application.yml:75-77`、设计 §11.6.1
- **说明**：设计要求 index 线程池 core=2/max=4/queue=5，实际 core=1/max=2/queue=100。并发索引能力被压缩，大文档批量发布时同步耗时会拉长。

---

### D. 安全合规

#### 🔴 D-1 [P0] ✅ Demo 用户硬编码明文密码 + ADMIN，且无条件初始化

- **位置**：`DemoUserInitializer.java:12-14`（`{noop}demo` + `ADMIN`）、`SecurityConfig` permitAll 含 `/kbs`、`/kbs/*`
- **问题描述**：
  1. 密码 `{noop}demo` 是**明文存储**（设计 §6.3.1 要求 BCrypt）；
  2. 默认 `ADMIN` 角色，任何能访问 8080 的人用 demo/demo 即获最高权限；
  3. `DemoUserInitializer` 无 `@Profile("dev")` 或环境开关，**生产部署也会初始化**；
  4. `SecurityConfig` 把 `/kbs`、`/kbs/*` 设为 permitAll（应为知识库列表，需登录），与 RBAC 矛盾。
- **影响**：严重安全漏洞——任何人 demo/demo 登录即 ADMIN，可管理全部知识库、查看 QaTrace。
- **修复建议**：

  ```java
  @Component
  @Profile("dev")  // 或 ConditionalOnProperty
  public class DemoUserInitializer ...
  ```

  并使用 BCrypt 加密 demo 密码；`SecurityConfig` 移除 `/kbs`、`/kbs/*` 的 permitAll。

#### 🟡 D-2 [P1] 用户管理接口无方法级权限注解，仅靠 Service 内 `requireAdmin()`

- **位置**：`AuthController.java:49-65`（`/users`、`/users/{id}/role`）、`AuthService.listUsers/createUser/updateUserRole` 内 `requireAdmin()`
- **问题描述**：`requireAdmin()` 在方法体内执行，先进入 Controller 再校验。虽能拦截，但：
  1. 与 Spring Security 惯例的 `@PreAuthorize("hasRole('ADMIN')")` 不一致；
  2. `SecurityConfig` 的 `anyRequest().authenticated()` 只校验登录，未声明角色，纵深防御缺失。
- **评估**：功能上安全（service 层兜住了），但属规范问题，建议补 `@PreAuthorize`。

#### 🟢 D-3 [P2] ✅ SpikeController 默认启用且暴露在 `/api/spike`

- **位置**：`SpikeController.java:22`（原 `@ConditionalOnProperty(... matchIfMissing = true)`）
- **问题描述**：W1 技术 Spike 的验证代码默认开启，暴露 `/api/spike/vectors/seed`、`/api/spike/vectors/search`、`/api/spike/chat/stream`。虽然受 JWT 保护，但：
  1. Spike seed 写入的是 `doc-spike-handbook` 固定 ID，会污染向量表；
  2. 生产环境不应暴露调试端点。
- **修复建议**：改为 `havingValue = "true"` 且**去掉 `matchIfMissing = true`**，仅在显式配置 `KNOWSOURCE_SPIKE_ENABLED=true` 时启用。`r`n- **P2 安全收口修复（2026-06-26）**：已移除 `matchIfMissing=true`，SpikeController 仅在 `knowsource.spike.enabled=true` 时注册；`application.yml` 继续默认 `KNOWSOURCE_SPIKE_ENABLED=false`。

#### 🟢 D-4 [P2] ✅ JWT secret 默认值硬编码

- **位置**：`application.yml:83`（原 `dev-only-change-me-dev-only-change-me`）、`JwtService.java:29`
- **评估**：有长度校验（≥32 字符）且通过环境变量覆盖，符合设计「环境变量注入」要求。但默认值偏弱，建议生产 profile 强制要求环境变量存在（缺失则启动失败）。`r`n- **P2 安全收口修复（2026-06-26）**：`application.yml` 将 `knowsource.security.jwt.secret` 默认值改为空 `${KNOWSOURCE_JWT_SECRET:}`，缺失时由 `JwtService.requireSecret` 启动失败；`application-db.yml` 单独配置测试专用 secret，保证自动化测试稳定。

---

### E. 代码质量与可维护性

#### 🟡 E-1 [P1] ✅ 异常处理每个 Controller 重复，无全局 `@RestControllerAdvice`

- **位置**：6 个 Controller 各自重复 `@ExceptionHandler(IllegalArgumentException / ResourceNotFoundException / AccessDeniedException)`
- **问题描述**：6 处几乎相同的异常处理代码，违反 DRY。新增 Controller 需复制粘贴，易遗漏。
- **修复建议**：提取 `@RestControllerAdvice` 统一处理，同时落地设计 §9.1 的统一响应体 `{code, message, data, timestamp}`（当前返回 `{message}`，与设计不符）。
- **MVP 收尾修复（2026-06-26）**：新增 `GlobalExceptionHandler` + `ApiErrorResponse`，统一处理 `IllegalArgumentException`、`BadCredentialsException`、`AuthenticationCredentialsNotFoundException`、`AccessDeniedException`、`ResourceNotFoundException`、`IOException`、`LlmUnavailableException`。移除 Auth/KB/Document/Chat/QaTrace/Eval Controller 内重复 `@ExceptionHandler`。

#### 🟡 E-2 [P1] ✅ 响应格式与设计 §9.1 不一致

- **位置**：各 Controller 返回裸 record / `{message}`
- **问题描述**：设计 §9.1 要求统一响应 `{code:0, message:"success", data:{}, timestamp}`，错误码 40001/40100/40300 等。实际无此封装。
- **评估**：属规范偏差，但影响前端契约一致性。
- **MVP 收尾修复（2026-06-26）**：为避免破坏既有前端和成功响应契约，本次先统一错误响应为 `{code, message, timestamp}`，保留成功响应裸 record。错误码覆盖 MVP 常见路径：40001、40100、40300、40400、50002。

#### 🟢 E-3 [P2] `VectorIndexService.vectorLiteral` 与 `VectorSearchService.vectorLiteral` 重复

- **位置**：`VectorIndexService.java:130-139`、`VectorSearchService.java:181-190`
- **修复建议**：提取为工具类（与 A-3 一起，集中到 `KnowSourceVectorStore`）。

#### 🟢 E-4 [P2] 手写 JSON 拼接存在注入/转义风险

- **位置**：`DocumentService.parentMetadataJson / childMetadataJson`、`VectorIndexService.metadataJson`
- **问题描述**：用字符串模板拼 JSON（`"\""+value.replace(...)+"\""`），而非 `ObjectMapper`。虽有基础转义，但 `kbId` / `chunkId` 等若含特殊字符（如控制字符），拼出的 JSON 可能损坏。
- **修复建议**：统一用 `ObjectMapper.writeValueAsString(Map)` 构造 metadata。

---

## 三、优化建议

### 立即优化（MVP 必须）

| # | 建议 | 对应问题 | 状态 |
|---|------|----------|------|
| 1 | 引入 Actuator + Micrometer + Prometheus，在检索/生成/索引关键路径埋 Timer/Counter | A-2 | ✅ 已完成 |
| 2 | 修复 Demo 用户：加 `@Profile("dev")`、BCrypt 加密、移除 `/kbs` permitAll | D-1 | ✅ 已完成 |
| 3 | 区分「无 LLM Key」与正常态：无 Key 时显式 50002 报错，不静默占位 | B-1 | ✅ 已完成 |
| 4 | `SpikeController` 去掉 `matchIfMissing=true`，默认关闭 | D-3 | ✅ 已完成（P2 安全收口） |
| 5 | JWT 加入 `tv` 字段并在 filter 校验 token_version | B-3 | ✅ 已完成（MVP P1 收尾） |
| 6 | 提取 `@RestControllerAdvice` 统一异常处理 + 统一响应体 | E-1 / E-2 | ✅ 已完成（错误响应统一；成功响应保持兼容） |
| 7 | 在 ADR/README 显式记录「手写 RAG 流水线 vs Advisor 链」的决策与代价，对齐文档与代码 | A-1 | ✅ 已完成 |
| 8 | 收紧库外拒答词项 gate，补充泛词误召回测试 | B-2 | ✅ 已完成（MVP P1 收尾） |

### 后续演进（V1.1）

| # | 建议 | 价值 |
|---|------|------|
| 1 | 重构为 `RetrievalAugmentationAdvisor` + `ContextualQueryAugmenter(allowEmptyContext=false)`，回归设计 §7.7 | RAG 效果 + 叙事一致性 |
| 2 | 落地 `OverlapTokenSplitter`，提供 overlap=0/80 对照评测 | 召回质量 +3% |
| 3 | 实现 `IndexQueryService` Caffeine 缓存，发布时主动失效 | 检索性能 |
| 4 | 补充 `spring-ai-rag`、`Knife4j`、改密接口 + token 黑名单 | 功能完整性 |
| 5 | 混合检索（pgvector + tsvector + RRF）、MultiQuery 并行 embed | RAG 效果 |
| 6 | 解析任务僵死回收 + `published_version / indexing_version` 双版本无中断发布 | 可用性 |

---

## 四、验证建议（核心链路测试用例）

> 现有测试已覆盖大部分场景，以下补充**设计强调但当前缺失**的验证点。

### 链路 1：草稿物理不可检索（延迟向量化）

现有 `refusesWhenOnlyDraftDocumentMatches` ✅ 已覆盖。建议追加：**直接查 `vector_store` 表确认草稿 doc_id 无任何行**（`DocumentPublishControllerTest` line 79 已断言，可保留）。

### 链路 2：库外问题 100% 拒答

现有 `refusesChineseOutOfScopeQuestionWhenVectorSearchReturnsUnrelatedChunk` ⚠️ 部分覆盖。

**补充用例**：召回相关 chunk 但语义无关（如问「年假」，召回「病假」chunk 且词项重叠），验证不误放行——这是 `hasLexicalEvidence` 词项 gate 的**已知弱点**。

### 链路 3：上传 PDF → 发布 → 单轮问答 → 多轮追问 → 下架

```
1. POST /api/kbs/{kbId}/documents/upload (请假制度.pdf) → 202 taskId
2. 轮询 /api/documents/{docId}/ingest-task → READY
3. 查 vector_store WHERE doc_id=? → 0 行（验证延迟向量化）✅
4. POST /api/documents/{docId}/publish → 202 indexStatus=PENDING
5. 轮询 index-status → SYNCED
6. 查 vector_store WHERE doc_id=? → N 行（验证 Outbox 消费）
7. POST /api/kbs/{kbId}/chat {question:"年假几天"} → 单轮 naive + sources ✅
8. POST /api/kbs/{kbId}/chat {question:"它的流程", sessionId} → 多轮 modular + rewrittenQuery ✅
9. POST /api/documents/{docId}/archive → vector_store 归零 ✅
10. 再次问答 → 拒答 ✅
```

**关键缺口**：步骤 7/8 当前因 B-1 **无法验证真实 LLM 答案**，需先配 Key。

### 链路 4：越权与权限隔离

- VIEWER 调上传接口 → 403 ✅（`viewerCannotWrite` 已覆盖）
- **补充**：VIEWER 调 `/api/auth/users` → 403 ✅（已覆盖）
- **补充**：跨知识库问答（用户是 KB-A 成员，用 KB-A 的 kbId 请求，但 sources 不应含 KB-B 文档）→ 当前 SQL 已 `WHERE vs.kb_id=:kbId` 隔离，建议补集成测试固化。

### 链路 5：Outbox 失败重试与僵死回收

现有 `outboxFailureUsesConfiguredExponentialRetryBackoff`、`processingRecoversStaleSyncingEventBeforeClaimingNextEvent` ✅ 覆盖优秀，**建议保留**。

---

## 五、值得肯定的最佳实践（设计未明确但实现优秀）

1. **`failureMessage` 遍历完整 cause 链**（`DocumentIndexOutboxService.java:273-298`）：修复了「只取 root cause 时消息丢失」的常见坑，注释解释清晰，工程意识强。
2. **OSS / Local 存储的路径穿越防护**（`safeSegment` / `ensureWithinRoot` / `sanitizeFilename`）：双校验，安全性扎实。
3. **检索 SQL 的 `(doc_id, doc_version)` 精确 JOIN**（`VectorSearchService:107-122`）：JOIN documents 同时匹配 `id + version + kb_id` 三列，杜绝跨文档同版本串召回，符合 ADR-008。
4. **AI 调用三维度韧性**（`AiProviderResilience`）：RateLimiter + Bulkhead + Retry 组合，且 chat/rerank 不重试（流式友好）、embedding 重试，区分得当。
5. **Rerank 降级链**（`DashScopeDocumentReranker` → `LexicalDocumentReranker`）：API 失败回退词项重排，保证可用性，符合设计 §7.4.4。
6. **测试用 `@TestConfiguration` 注入 Fake Embedding**：避免依赖真实 DashScope，集成测试可独立运行，质量高。

---

## 六、总结

该 MVP 在**延迟向量化、Outbox 状态机、检索安全 Filter、RBAC、QaTrace、测试覆盖**这六大支柱上实现扎实，工程完成度高。主要差距集中在：

1. **未用 Spring AI Advisor 链实现 RAG**
2. **可观测性缺失**
3. **LLM 答案生成降级语义**

建议优先补齐 Actuator / Micrometer（A-2）、修复 Demo 用户安全（D-1）、明确 LLM 可用性（B-1），并在文档层面对齐 RAG 实现决策（A-1），即可达到可投递状态。

### P0 修复进展（2026-06-26 更新）

上述 4 项 P0 问题（A-1、A-2、B-1、D-1）**已全部修复并验证**，修复细节见 [附录：P0 修复记录](#附录p0-修复记录)。验证结果：**86 个测试全部通过（0 失败，0 错误）**。2026-06-26 继续完成 MVP P1 收尾：B-2、B-3、E-1/E-2 已修复，详见 [附录：MVP P1 收尾修复记录](#附录mvp-p1-收尾修复记录)。

---

## 附录：P0 修复记录

> 修复日期：2026-06-26　|　验证：`mvn test` 86 passed / 0 failed / 1 skipped（OSS smoke test，无凭证跳过，与本次无关）

### A-1 ✅ RAG 核心未采用 Spring AI Advisor 链 —— 文档对齐

- **修复方式**：文档对齐（采纳报告「至少在 ADR 中明确记录」的建议）。
- **改动**：增强 `docs/architecture/ADR-001-production-rag-path.md`，新增「Costs and gaps versus design §7.7」章节，显式记录：
  - 偏离设计 §7.7（`RetrievalAugmentationAdvisor`）的**原因**——SQL-first 服务（`VectorIndexService`/`VectorSearchService`/`ChatService`）必须显式持有 `kb_id` 绑定、`(doc_id, doc_version)` 精确匹配、`index_status` 过滤、拒答/引用/流式/QaTrace 等生产控制点，路由进 Advisor 内部会使这些控制难以测试与审计。
  - 已知**代价**：`ContextualQueryAugmenter.allowEmptyContext(false)` 未落地，当前以 `hasLexicalEvidence` 词项 gate 作为过渡桥，记录为已知 MVP shortcut。
  - 未来重新引入 Advisor 链时的**约束**：必须桥接到现有 SQL-first 服务，并保留 citation/refusal/trace/ACL/version-matching 全部保证，不得引入第二条检索/索引路径。
- **效果**：消除「文档说一套、代码做一套」的叙事风险；`hasLexicalEvidence` 桥的淘汰路径已明确。

### A-2 ✅ 可观测性缺失 —— 集成 Actuator + Micrometer + Prometheus

- **修复方式**：依赖引入 + 配置 + 关键路径埋点。
- **改动**：
  - `pom.xml`：新增 `spring-boot-starter-actuator`、`micrometer-registry-prometheus`。
  - `application.yml`：暴露 `health,info,prometheus,metrics` 端点；为 `knowsource.rag.retrieval`、`knowsource.rag.generation_first_token`、`knowsource.ingest.index.success` 配置 P50/P95 直方图分位（对齐设计 §11.2）。
  - `ChatService`：注入 `MeterRegistry`，检索路径记录 `knowsource.rag.retrieval` Timer（tag: kbId/profile），流式首 token 记录 `knowsource.rag.generation_first_token` Timer（仅在真实 LLM 生成、非拒答/非 fallback 时记录）。
  - `VectorIndexService`：注入 `MeterRegistry`，索引路径记录 `knowsource.ingest.index.success` Timer（tag: kbId/outcome=success|failure），成功失败均埋点。
  - `SecurityConfig`：放行 `/actuator/health|info|prometheus|metrics` 供 Prometheus 抓取。
- **效果**：`/actuator/prometheus`、`/actuator/metrics` 可用，关键链路具备时序指标，满足设计 §11.2 与 §2.5 的 KSI 监控诉求。

### B-1 ✅ LLM 答案生成降级为占位文本 —— 无 Key 时显式报错

- **修复方式**：无 LLM generator 时硬失败，而非静默占位。
- **改动**：
  - 新增 `LlmUnavailableException`（错误码 `50002`）与 SSE `ChatStreamError` 事件。
  - `ChatService.answer()`：检索到上下文但 `answerGenerator == null` 时抛 `LlmUnavailableException`（不再返回占位文本）。
  - `ChatService.streamContext()`：无 `streamingAnswerGenerator` 时发送 `error` 事件（含 code/message/qaTraceId）后完成，不再发伪流式占位。
  - `ChatController`：`LlmUnavailableException` → HTTP `503 Service Unavailable`，响应体 `{code:50002, message}`。
  - `AnswerGenerator` 由 package-private 改为 `public`（跨包测试需提供 fake 实现）。
  - 新增测试 `ChatLlmUnavailableTest`：验证无 generator 时非流式返回 503 + code 50002、流式发送 error 事件。
  - 修正 `ChatControllerTest`：`FakeEmbeddingConfig` 注入 fake `AnswerGenerator`，断言从「占位文本」改为「真实生成答案 + `llmMs` 非零」；`EvalRunnerTest`/`EvalControllerTest` 同步注入 fake generator。
- **效果**：核心问答闭环不再静默退化；无 Key 配置时立即暴露配置错误，避免「检索到片段但不回答」的演示事故与误导性评测。

### D-1 ✅ Demo 用户硬编码明文密码 + ADMIN，无条件初始化 —— 安全收紧

- **修复方式**：BCrypt 加密 + 默认关闭开关 + 收紧授权。
- **改动**：
  - `DemoUserInitializer`：密码改用注入的 `PasswordEncoder` 生成 BCrypt 哈希（告别 `{noop}` 明文）；新增 `@ConditionalOnProperty(knowsource.demo-user.enabled, havingValue=true)`，**默认关闭**（无 `matchIfMissing`），生产部署默认不再初始化 demo 用户。
  - 配置：`application.yml` 默认 `KNOWSOURCE_DEMO_USER_ENABLED=false`（生产安全）；`application-db.yml`（测试）/`application-local.yml`（本地）显式 `true`，保持既有开发体验。
  - `SecurityConfig`：移除 `/kbs`、`/kbs/*` 的 `permitAll`（知识库列表需登录），与 RBAC 一致。
- **效果**：消除「任何人 demo/demo 即 ADMIN」的严重安全漏洞；生产默认无 demo 用户，仅本地/测试显式启用；密码以 BCrypt 存储。

### 验证结果

```
mvn test
Tests run: 86, Failures: 0, Errors: 0, Skipped: 1
BUILD SUCCESS
```

覆盖本次改动的关键测试类：`ChatControllerTest`(23)、`ChatAnswerFallbackTest`(1)、`ChatLlmUnavailableTest`(2，新增)、`AuthSecurityTest`(6)、`EvalRunnerTest`(1)、`EvalControllerTest`(1)、`DocumentPublishControllerTest`(11)、`KnowledgeBaseControllerTest`(7) 等全部通过。

---

## 附录：MVP P1 收尾修复记录

> 修复日期：2026-06-26　|　阶段验证：`mvnw.cmd "-Dtest=AuthSecurityTest,ChatControllerTest" test`，31 passed / 0 failed / 0 skipped；全量验证：`mvnw.cmd test`，88 passed / 0 failed / 1 skipped。

### B-2 ✅ 库外拒答词项 gate 过宽 —— 收紧强证据判断

- **修复方式**：保留 MVP 手写 RAG 流水线，但将拒答 gate 从“任意 query 词项命中 chunk”收紧为“过滤泛化弱证据词后仍有强证据词命中”。
- **改动**：`ChatService` 新增弱证据词/短语过滤，覆盖中文 bigram 场景（如“审批流程”拆出的桥接词），并继续叠加 `VectorSearchService` 的 `max-distance` 向量阈值。
- **测试**：新增 `refusesChineseQuestionWhenOnlyWeakGenericTermsOverlap`，验证问题“报销审批流程是什么？”不会因为请假制度 chunk 中出现“审批流程/制度/员工/申请”等泛词而被放行。
- **剩余说明**：这仍是 MVP shortcut，不等同于最终架构的 `ContextualQueryAugmenter.allowEmptyContext(false)`；后续回归 Advisor 链时应淘汰该手写 gate。

### B-3 ✅ `token_version` 未参与 JWT 校验 —— 旧 token 失效闭环

- **修复方式**：JWT payload 写入 token version，并在认证过滤器中查库比对。
- **改动**：`CurrentUser` 增加 `tokenVersion`；`AuthService` 登录/注册/刷新查询 `users.token_version`；`JwtService.createAccessToken(...)` 写入 `tv`；`JwtAuthenticationFilter` 解析 token 后查库校验 `uid/global_role/token_version`，不一致则清空认证上下文。
- **测试**：新增 `roleChangeInvalidatesExistingAccessToken`，验证管理员更新用户角色后，旧 accessToken 访问受保护 API 返回 401，重新登录签发的新 token 可用。

### E-1 / E-2 ✅ Controller 异常处理重复、错误响应不统一 —— 全局错误响应

- **修复方式**：先统一错误响应，不破坏现有成功响应契约。
- **改动**：新增 `ApiErrorResponse { code, message, timestamp }` 与 `GlobalExceptionHandler`；统一处理 400/401/403/404/503 常见异常；移除 Auth、KnowledgeBase、Document、Chat、QaTrace、Eval Controller 内重复 `@ExceptionHandler`；Spring Security `AuthenticationEntryPoint` 也返回同结构 401 JSON。
- **错误码**：40001（参数错误）、40100（认证失败）、40300（权限不足）、40400（资源不存在）、50002（LLM generator 不可用）。
- **测试**：更新 `AuthSecurityTest` 与 `ChatControllerTest`，断言错误响应包含 `code/message/timestamp`。

---

**文档结束**

*KnowSource — 企业知识，有据可查。*

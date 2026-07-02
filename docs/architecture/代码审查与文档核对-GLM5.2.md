# 审查规划(5 次)

代码规模:**112 个 Java 文件 / 8901 行 / 21 个测试**,外加前端和 9 个迁移。我按"架构层 ↔ 代码模块"一一对应来切分,每次范围独立、可单独交付结论:

| #     | 审查范围                            | 对应代码                                                  | 对照文档章节                | 重点产出                                                     |
| :---- | :---------------------------------- | :-------------------------------------------------------- | :-------------------------- | :----------------------------------------------------------- |
| **1** | **数据模型 + 基础设施**             | 9 个 Flyway 迁移、`application.yml`、`pom.xml`、`config/` | §6.3–6.4、§13.3、§4.3、§5.2 | schema 与文档全量对账;V3–V9 文档缺失项;依赖版本锁定;角色反复横跳的最终态 |
| **2** | **认证授权 + 知识库**               | `auth/` `security/` `user/` `kb/`                         | §8、§9.2                    | JWT/TTL/refresh/token_version 是否落地;RBAC 与代码最终角色模型;kbId 服务端绑定 |
| **3** | **入库 + 索引(文档生命周期)**       | `document/`(26)、`index/`(9)                              | §4.4.1、§6.2、§7.2、§7.3    | 延迟向量化、Outbox 状态机、Parent-Child 切块、OverlapSplitter 语义、delete→add 恢复路径 |
| **4** | **问答与检索(RAG 核心)**            | `chat/`(38)                                               | §4.4.2、§7.4–7.7、§11.1     | auto 路由、Filter `(doc_id,doc_version)`、Rerank 降级、PostProcessor 早推 sources、SSE、QaTrace、**混合检索(V8 是否已接入问答链路)** |
| **5** | **评测 + 前端 + 测试 + 部署(收尾)** | `eval/`、`src/test/`、`frontend/`、docker                 | §10、§12、§13               | EvalRunner、测试覆盖与 mock 策略、前端 SSE 消费、部署一致性、文档完整性总账 |

**为什么这样分:**

- 第 1 次最轻但信息密度最高——一次性把"文档 vs 代码 schema 差距"摸清,为后续每次审查提供数据模型基准;
- 第 3、4 次是 RAG 核心两侧(入库 / 问答),文件最多,单独成次;
- 第 4 次内置一个**重大悬念**待解:V8 的混合检索到底有没有接进问答链路(若有,文档 §7.4.5 / §2.4 的"Out of Scope"就是错的);
- 第 5 次收尾,顺带出一份**文档完整性补丁清单**(哪些章节必须改)。

每次审查输出:① 文档与代码的偏差清单(带 `file:line`)② 潜在缺陷 ③ 建议修订项

# 第 1 次审查报告:数据模型 + 基础设施

审查对象:9 个 Flyway 迁移、`application.yml` / `application-db.yml` / `application-local.yml`、`pom.xml`、`config/` 模块、`docker-compose.yml`。 对照文档:§4.3、§5.2-5.3、§6.3-6.4、§8.5、§11.6、§13。

发现按严重程度分级:**🔴 严重 / 🟠 重要 / 🟡 一致性 / 🟢 优化**。

## 🔴 严重问题

### S1. 真实 API Key 泄露在 git 跟踪的文件中

`application-db.yml:4` 的同名文件**已在 git 中**(commit `607a580` 等),其姐妹文件 `application-local.yml:4` 含**真实 DashScope API Key**(`sk-ws-H.RPPLDDI...`)。虽然 `.gitignore` 忽略了 `application-local.yml`,但:

- `application-local.yml` 是**当前唯一含真实 key 的文件**,一旦有人误 `git add -f` 或换工具链就泄露
- 文档 §8.5 明确「API Key 环境变量注入,禁止提交 Git」——当前靠 `.gitignore` 一道防线,且 db profile 仍跟踪
- 该 key 在历史 commit 里也能查到的话,即便现在删除也已留存

**必须处理:**

1. 立即**吊销该 key 并重发**(阿里云控制台 → API-KEY 管理 → 删除/禁用);
2. `application-local.yml` 保持忽略,但 key 改为 `${AI_DASHSCOPE_API_KEY}` 引用,真实值只放 `.env`(本地)或环境变量;
3. 用 `git filter-repo` / BFG 清理 git 历史中的 key(如果历史 commit 含明文);
4. `application-db.yml` 也加入 `.gitignore`,或确保它只放非敏感测试值。

### S2. 文档核心设计 `KnowSourceVectorStore` 根本不存在,但代码用了另一套方案——文档全面失真

文档在 §6.4、ADR-001(L562)、§7.3.2 反复强调:

> 必须自定义 `KnowSourceVectorStore extends PgVectorStore`,重写 `doAdd()` / `doSearch()`,否则冗余列 B-tree 索引失效。

代码实际:`find` 找不到任何 `*VectorStore*` 自定义类;`VectorIndexService.java:84-103` **直接用 `JdbcClient` 手写 INSERT**,绕开 `VectorStore` 接口。这是合理的工程选择(比继承框架类更稳),但带来三个后果:

1. **文档严重失真**:面试官照着 §6.4 提问会答不上(代码不是这么实现的);
2. `VectorStore` 接口在 main 代码中**唯一引用点是 `SpikeController.java`**(W1 验证用),生产链路完全不走接口;
3. ADR-001 说的「保留 `VectorStore` 接口,换 Bean 即可迁移」**不成立**——迁移向量库要改 `VectorIndexService` 手写 SQL + 检索层(下次审查查检索是否也绕开了)。

**建议:** 文档要么改写为「`JdbcClient` 直写方案」(贴合代码),要么补一个 ADR 说明为什么放弃继承方案。这是文档 vs 代码差距最大的一处。

## 🟠 重要问题

### I1. 线程池全部缺少 `RejectedExecutionHandler`,与文档 §11.6.1 不符

文档 §11.6.1 表格明确写「拒绝策略 = CallerRunsPolicy」,并强调这是「上传限流」「排队等待」的关键。但 `AsyncConfig.java:43-54` 的 `taskExecutor()` 工厂方法**没有调用 `setRejectedExecutionHandler`**。

Spring `ThreadPoolTaskExecutor` 默认是 `AbortPolicy`——**队列满时抛 `RejectedExecutionException`,而非文档描述的"由调用线程执行"**。后果:

- `ingestExecutor` 队列满 → 上传接口直接抛异常(而非降级同步执行);
- `chatExecutor` 队列满 → 问答请求被拒(而非排队);
- 与文档「CallerRunsPolicy」承诺的可用性行为完全相反。

`AsyncConfig.java:48` 的 `Math.max(corePoolSize, maxPoolSize)` 也吞掉了配置错误(若有人误把 max < core,不报错而是静默修正)。

**建议:** 显式 `executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy())`,或在 yml 暴露拒绝策略枚举。

### I2. `application-db.yml` 的自动配置 exclude 与主 yml 矛盾

- `application.yml:42`:`initialize-schema: false`(保留 PgVectorStoreAutoConfiguration)
- `application-db.yml:78`:**exclude `PgVectorStoreAutoConfiguration`**

两者都声称关闭 schema 初始化,但手段不同。若 db profile 激活时,`VectorStore` Bean 根本不存在,而 main 代码唯一用 `VectorStore` 的 `SpikeController` 会启动失败。需确认 db profile 是否仅用于测试(`src/test` 下)。如果生产/演示也用 db profile,Spike 会炸;如果仅测试用,应在文件头注明。**这是一处隐性启动陷阱。**

### I3. 全局角色 EDITOR「删了又加」(V7→V9),文档权限矩阵过时

- V7 删除 `global_role='EDITOR'`,约束只剩 `('ADMIN','VIEWER')`
- V9 又恢复为 `('ADMIN','EDITOR','VIEWER')`

但文档 §8.2.1(L1439)的权限矩阵**完全没有 EDITOR 列**,§3.1.1(L180)明确「全局角色只区分 ADMIN 与 VIEWER」。即文档说"只有 ADMIN/VIEWER",代码最终态允许 EDITOR。三方不一致(V7 中间态、V9 最终态、文档)。第 2 次审查(认证授权)会重点核对 EDITOR 角色在代码里实际有没有权限逻辑,这里先标记。

### I4. 文档完全缺失 V3–V9 七个迁移的说明

文档 §13.3 只描述 V1/V2,实际工程有 9 个迁移。缺失项里几个是**重大功能**:

- V3: `chat_sessions` / `chat_messages`(文档 §6.3.10 有表,但没说在 V3 迁移)
- V5: `chat_messages.qa_trace_id`(文档完全没提这列)
- V6: `ingest_tasks` 增加 10 个质量报告列(`page_count`/`ocr_*`/`quality_report JSONB`)——文档 §6.3.6 完全没提,且暗示已实现 OCR 质量统计,但 §2.4 说 OCR 是 Out of Scope
- V8: **混合检索 FTS**(`content_tsv tsvector` + GIN 索引)——文档 §7.4.5 明确「混合检索 v1.1 才做」、§2.4 列为 Out of Scope,**代码已落地且 `application.yml:124` 默认 `retrieval.mode=hybrid`**

这是**代码已大幅超前于文档的设计边界**的最强证据。第 4 次审查会确认混合检索是否真接入了问答链路。

## 🟡 一致性问题(文档措辞错误,应修正)

### C1. Flyway 迁移命名——已确认 §13.3 是对的,其余是历史残留

回答你的问题1:**§13.3(`V1__vector_store` + `V2__business_tables`)是最终正确版本**,代码 9 个文件命名一致。文档其他位置全是历史残留,应删除:

- §5.3 L542 注释 `V2__vector_store.sql` ❌
- §6.4 L834 `V2__vector_columns.sql`(ALTER 扩展列)❌
- 附录 D 标题 `V1__init_business.sql` ❌

### C2. §6.4「扩展冗余列」措辞与实际「完整自建」矛盾

§6.4 说「默认表经 Flyway 扩展冗余列」暗示 `ALTER TABLE`,实际 V1 是**完整 `CREATE TABLE`** 且 `initialize-schema=false`。措辞应改为「由 Flyway 完整建表(含冗余列)」。这点和我上次文档审查的结论一致,代码实锤了。

### C3. `application.yml` 默认值与文档多处不符

| 配置项              | 文档值                      | 代码 `application.yml` 实际                   | 说明                                                         |
| :------------------ | :-------------------------- | :-------------------------------------------- | :----------------------------------------------------------- |
| Access Token TTL    | §8.6.2 `86400s (24h)`       | `900s (15min)` L84                            | **代码对、文档错**——我在文档审查时指出 24h 过长,代码已是 15min,文档需更新 |
| Refresh Token TTL   | 文档未写周期                | `7 days` L85                                  | 文档应补充                                                   |
| datasource 端口     | §4.5 `5432`                 | `15432` L5                                    | 容器映射 `15432:5432`(compose L10),文档没更新                |
| `multi-query.count` | §5.3 `multi-query-count: 2` | `knowsource.modular-rag.multi-query.count: 2` | 键名层级变了                                                 |
| embedding 限流      | §11.6.2 `limitForPeriod:20` | `5` L169                                      | 文档表格值与代码不符                                         |

### C4. `pom.xml` 用的是 OpenAI starter 而非文档承诺的 DashScope starter

文档 §5.2(L503-506):

![img](./material-icons/xml.svg)xml

```
<artifactId>spring-ai-alibaba-starter-dashscope</artifactId>
```

代码 `pom.xml:69` 实际:

![img](./material-icons/xml.svg)xml

```
<artifactId>spring-ai-starter-model-openai</artifactId>
```

即代码用 **OpenAI 兼容模式**(配 `base-url=dashscope.aliyuncs.com/compatible-mode`,见 `application.yml:24`)访问 DashScope,而非 spring-ai-alibaba 原生 starter。这是合理选型(避免 alibaba starter 版本耦合),但**文档 §5.1 选型表、§5.2 依赖、§4.3 技术架构表全部写错**。文档审查时我基于文档以为是 alibaba starter,现需更正。

### C5. docker-compose.yml 缺 app 服务

文档 §13.1 的 compose 包含 `postgres` + `app` 两个服务。实际 `docker-compose.yml` **只有 postgres**(21 行)。即「一键部署」目前只起数据库,应用需手动 `mvn spring-boot:run`。文档 §2.3 F-「Docker Compose 一键部署」、§13.2 启动步骤「`docker compose up -d --build`」**都不成立**。

## 🟢 优化建议

### O1. `metadataJson()` 手拼 JSON 字符串,建议改用 Jackson

`VectorIndexService.java:182-198` 手拼 JSON,`jsonEscape` 只处理 `\` 和 `"`。虽然因参数化 SQL 实际无注入风险,但:

- 单引号、控制字符未转义,若 `chunk.content` 含特殊字符可能产生非法 JSON 落库;
- `pageNumber` 直接 `.toString()` 拼接已是数字字面量,尚安全,但手拼整体脆弱。 建议用 `ObjectMapper.writeValueAsString(map)`,与项目其他 JSON 处理一致。

### O2. `vectorLiteral()` 手拼向量字面量,建议批量 INSERT

`VectorIndexService.java:171-180` 逐 chunk 拼 `[v1,v2,...]` 并单条 INSERT(L82 循环)。千 chunk 文档会产生千次 round-trip。可改 PG 多值 INSERT 或 `COPY`。MVP 可接受,留作优化。

### O3. 缺少运维关键指标的迁移埋点

- Outbox 积压量(PENDING/FAILED 事件计数)无对应指标,`idx_publish_events_sched` 索引建了但没有 gauge 暴露积压;
- 向量表行数无指标。 建议在 actuator 加 `@Scheduled` gauge。

### O4. `qa_traces.session_id` 无外键,且 V3 才补 `chat_sessions`

`qa_traces` 在 V2 建表时 `session_id VARCHAR(36)` 无 FK;V3 建了 `chat_sessions` 但没回补 FK。孤儿 trace 风险低(查询走 session_id),但文档 §6.3.11 应注明「无 FK 约束,应用层保证」。

------

## 第 1 次审查总结

| 维度           | 结论                                                         |
| :------------- | :----------------------------------------------------------- |
| **数据模型**   | 9 个迁移整体设计扎实,索引合理(复合索引、partial index、GIN 都到位)。**远超文档覆盖范围**(V3–V9 文档完全没写)。 |
| **文档对齐度** | **严重失真**。最严重是 `KnowSourceVectorStore`(文档核心设计)根本不存在,代码用 `JdbcClient` 直写;其次是混合检索/OCR 已落地但文档标 Out of Scope。 |
| **基础设施**   | 线程池拒绝策略缺失(I1)、API key 泄露风险(S1)、compose 不完整(C5)是三个必须修的点。 |
| **代码质量**   | `VectorIndexService` 用单事务保护 delete+insert,实际**比文档更安全**;但缺拒绝策略、手拼 JSON 是隐患。 |

**最紧急的三件事:**

1. 🔴 **吊销并轮换泄露的 DashScope key**(S1);
2. 🔴 **决定文档走向**:要么改文档贴合代码(直写 SQL + 混合检索),要么明确代码哪些是「超出 MVP 的实验」需回退;
3. 🟠 **补 `RejectedExecutionHandler`**(I1),否则高负载行为与文档承诺相反。

# 第 2 次审查报告:认证授权 + 知识库

审查对象:`auth/`(9)、`security/`(6)、`user/`(2)、`kb/`(7)、`web/GlobalExceptionHandler`,共 1435 行。 对照文档:§3.1、§8、§9.2、§11.6。

发现分级:**🔴 严重 / 🟠 重要 / 🟡 一致性 / 🟢 优化**。

## 🔴 严重问题

### S1. 检索层安全核心"kbId 服务端绑定"在本批次代码中完全缺失

文档 §4.2(L326)、§8.3、FR-12 反复强调:

> `kbId` 必须从已校验的 `ChatSession` 取得,不得直接采用请求体中的 `kbId`;请求体 `kbId` 仅做一致性校验。

但本批次审查的认证/权限代码里,`kbId` 的校验模式是 **`requireKbMember(kbId, userId)`**——即"调用方是不是这个 kb 的成员"。这套 RBAC 是对的,但它**只保证"用户能访问这个 kb",不保证"问答时检索的 kbId == 用户当前会话所属 kb"**。ChatController 在第 4 次审查才会看到,但这里先标记:**KB 权限服务里没有 `ChatSession.kbId` 绑定的痕迹**,知识库服务也查不到任何与 chat session 关联的逻辑。如果第 4 次确认 chat 链路直接信任请求体 `kbId`,就是文档反复警告的越权风险落地了。

### S2. Refresh Token 缺少轮换(rotation),且与文档"§8.6.2 失效策略"冲突

`AuthService.refresh()`(L94-121)的逻辑:

1. 校验旧 refresh token 有效 → `FOR UPDATE OF rt` 锁行
2. `UPDATE refresh_tokens SET revoked_at = NOW()` 作废旧 token ✅
3. `issueTokens()` 签发**新 access + 新 refresh** ✅

**这里代码是对的,实现了 rotation**。但有两处问题:

**(a) 文档没写 rotation,代码做了——文档需补**。文档 §8.6.2 只说"refresh 签发新 accessToken",没提是否作废旧 refresh token、是否签发新 refresh。代码实际做到了双轮换,是**代码优于文档**。我在第 1 次文档审查里把"缺 rotation"列为建议项,现在代码层面已解决,但文档仍需更新。

**(b) 严重的可用性缺陷:logout 后无法 refresh,但 access token 仅 15min**。

- access TTL = 900s(15min,`application.yml:84`)
- 用户 logout → `revoked_at = NOW()`
- 用户重新登录前,如果 access token 也过期,只能重新登录

这本身合理。**但真正的风险在另一处**:refresh 用 `FOR UPDATE OF rt` 单行锁(L105),如果同一用户并发发起两个 refresh 请求(前端重试/多标签页),第二个会因为 `revoked_at IS NULL` 失配而返回 "Invalid refresh token"——**用户正常的并发 refresh 会被误判为失败**,可能把用户踢下线。建议用幂等设计(返回同一未撤销 token)或显式处理竞态。

## 🟠 重要问题

### I1. 全局角色 EDITOR 的权限语义与文档完全不符 ⚠️(呼应第 1 次 I3)

代码中 EDITOR 角色有**实质性的全局权限**:

| 位置                                                         | EDITOR 能做什么                                      |
| :----------------------------------------------------------- | :--------------------------------------------------- |
| `KnowledgeBaseService.requireKnowledgeBaseCreateAccess` L378-382 | **全局 EDITOR 可以创建任意知识库**(并自动成为 OWNER) |
| `KnowledgeBaseService.listManageable` L322-333               | 全局 EDITOR 列出自己作为 OWNER/EDITOR 的 KB          |

但文档:

- §3.1.1 L180:「全局角色只区分平台 ADMIN 与普通用户 VIEWER」
- §8.2.1 权限矩阵:**没有 EDITOR 列**,「创建知识库」只打勾给 ADMIN
- §8.6.1:「禁止开放注册即 ADMIN」(隐含只有 ADMIN/VIEWER)

**三方不一致**:V7 删 EDITOR → V9 加回 → 代码给了 EDITOR 创建 KB 的权限 → 文档说没这角色。这是第 1 次审查 I3 的实锤。第 1 次提的"角色反复横跳"在此显现真实后果:**文档画的权限模型和代码跑的权限模型不是同一个**。面试官照文档问"EDITOR 能干嘛",无法作答。

**额外风险:** 全局 EDITOR 能创建 KB 意味着普通注册用户(若被设为 EDITOR)可无限建库,可能不符合"企业知识库平台"的治理预期。

### I2. `requireTargetEligibleForKbRole` 存在权限提升漏洞隐患

`KnowledgeBaseService.java:337-350`:把用户设为 KB 的 OWNER/EDITOR 时,要求目标用户全局角色不能是 VIEWER。但反过来:

- 全局 VIEWER 只能当 KB VIEWER ✅(防住了)
- 全局 EDITOR 能当 KB OWNER ✅
- 全局 ADMIN 能当 KB OWNER ✅

逻辑本身没错。**但配合 I1 的"全局 EDITOR 可创建 KB 并成为 OWNER"**,形成了一条权限提升链:

> 注册 → 全局 VIEWER → (被某 OWNER 设为 KB EDITOR)→ 仍不能创建 KB → 但若被全局 ADMIN 设为全局 EDITOR → 可创建 KB 并成为 OWNER → 可再把自己的 KB OWNER 给别人

这条链是否符合设计意图不明确。文档应明确"全局 EDITOR"的定位:是"可信内容贡献者"(可建库)还是"普通用户"?当前代码倾向前者,文档倾向后者。

### I3. KB 删除是手写级联,顺序脆弱且不完整

`KnowledgeBaseService.delete()` L111-173 手写了 10 条 DELETE,顺序依赖外键不存在(因为表间大多无 FK)。问题:

1. **顺序脆弱**:删除 `documents` 在删除 `chunk_children`/`chunk_parents`/`ingest_tasks` 之后(L160),但 `document_publish_events` 删除在 L136-141(更早)。如果中间某条抛异常,事务回滚没问题,但**顺序与依赖关系无关**,纯靠事务兜底;
2. **`vector_store` 删除是按 `kb_id`** L116-120 ✅ 正确(避免孤儿向量);
3. **但 OSS 原文件未清理**:delete KB 不删 OSS 对象,造成存储泄漏。文档 §6.5 OSS 路径是 `{kbId}/{docId}/{version}/`,删 KB 后这些对象永久残留。这点第 3 次审查(入库)需确认 `SourceStorageService` 是否有清理钩子;
4. **`refresh_tokens` 未清理**:删用户场景未涉及,但删 KB 时 `qa_traces.user_id` 仍指向已删数据——不过 `qa_traces` 在 V2 建表时 `user_id` 有 FK 到 users,删 KB 不删 user,所以暂时无孤儿。但 `qa_traces.session_id` 无 FK(L4 无 FK),删 session 后 trace 的 session_id 悬空,可接受(留作历史)。

### I4. 密码强度策略过弱,且与文档承诺的安全基调不符

`AuthService.normalizePassword` L225-231:**最小长度 4 位**,无复杂度要求。配合 demo 用户 `demo/demo`(L24-25),整个认证层的安全强度很低。文档 §8.5「数据安全」、§3.3「JWT + RBAC + refreshToken」营造了较严格的安全基调,但:

- 4 位密码可被秒级爆破(配合无登录限流,见 I5);
- 文档 §8.6.1 说 demo 环境 `admin/admin`,代码是 `demo/demo`,且角色是 **ADMIN**(L27)——demo 用户直接是管理员。

**建议:** MVP 至少要求 8 位 + 字母数字;demo 用户降为 VIEWER 或加醒目警告。

### I5. 无登录限流 / 账号锁定,密码爆破无防护

`AuthService.login` 无任何失败计数、IP 限流、账号锁定。`SecurityConfig` 也未见 rate limit 过滤器。Resilience4j 限流(`AiProviderResilience`)只保护 AI 调用,**不保护登录端点**。结合 4 位密码,登录接口可被脚本爆破。文档 §8 没提限流,但 §3.3「安全」需求下应补。

### I6. `JwtAuthenticationFilter` 吞掉 `AuthenticationException`,鉴权失败静默放行

`JwtAuthenticationFilter.java:45-47`:

![img](./material-icons/java.svg)java

```
} catch (IllegalArgumentException | AuthenticationException ignored) {
    SecurityContextHolder.clearContext();
}
```

鉴权失败时**清空 context 后继续 `filterChain.doFilter`**,后续走 `anyRequest().authenticated()` 会返回 401——**这个流程是对的**。但 `ignored` 变量名暗示作者认为这是"可忽略"的。问题在于:

- 没有任何日志记录,无法发现 token 失败模式(被盗?过期?篡改?);
- `parseAccessToken` 把所有异常都包成 `IllegalArgumentException`(L67-69),丢失了"过期"vs"签名错"vs"格式错"的区分,排障困难。

**建议:** 至少 `log.debug` 记录失败原因;区分过期与其他错误(过期可触发前端 refresh,其他应强制登出)。

## 🟡 一致性问题(文档 ↔ 代码)

### C1. API 路由全面不一致 ⚠️ 文档 §9.2 基本作废

文档 §9.2 与代码实际路由对照:

| 功能     | 文档路径                                               | 代码实际                                                     | 差异                                                         |
| :------- | :----------------------------------------------------- | :----------------------------------------------------------- | :----------------------------------------------------------- |
| 创建 KB  | `POST /kb`                                             | `POST /api/kbs`                                              | 路径不同                                                     |
| KB 列表  | `GET /kb`                                              | `GET /api/kbs`(仅自己成员的)                                 | 路径+语义                                                    |
| 上传文档 | `POST /kb/{kbId}/documents/upload`                     | `POST /api/kbs/{kbId}/documents/upload`                      | 嵌套层级                                                     |
| 文档列表 | `GET /kb/{kbId}/documents`                             | `GET /api/kbs/{kbId}/documents`                              | 同上                                                         |
| 发布     | `POST /documents/{docId}/publish`                      | `POST /api/documents/{docId}/publish`                        | 路径不同                                                     |
| 问答     | `POST /chat/stream`                                    | `POST /api/kbs/{kbId}/chat/stream`                           | **kbId 进了路径**                                            |
| QaTrace  | `GET /traces/{id}`(ADMIN)                              | `GET /api/kbs/{kbId}/qa-traces`(KB 成员)                     | **权限模型不同**                                             |
| 用户管理 | `POST /admin/users/{id}/role`                          | `PUT /api/auth/users/{userId}/role`                          | 路径+方法                                                    |
| 登录响应 | `{accessToken, refreshToken, expiresIn, role, userId}` | `AuthResponse(accessToken, refreshToken, id, username, globalRole)` | **字段名不同**(`id` vs `userId`,`globalRole` vs `role`,无 `expiresIn`) |

文档 §9.2 几乎**整节都需要重写**。值得注意的是:代码把 chat 和 qa-traces 都嵌在 `/api/kbs/{kbId}/...` 下,这其实是个**更好的设计**(kbId 进路径,天然绑定资源),但与文档完全相反——文档 §4.2 明确说"kbId 不信任请求体,从 ChatSession 取"。代码把 kbId 放进路径参数,等于**绕过了文档的 S1 设计**,实际靠 `requireKbMember` 兜底。这一点第 4 次审查要重点确认。

### C2. 登录响应字段与文档 §9.2 不符

文档 §9.2 登录响应:`{ accessToken, refreshToken, expiresIn, role, userId }` 代码 `AuthResponse`:`accessToken, refreshToken, id, username, globalRole`

- 缺 `expiresIn`(前端无法知道何时该 refresh,只能等 401);
- `id` 而非 `userId`;
- `globalRole` 而非 `role`;
- 多了 `username`。

前端若按文档开发会拿不到字段。

### C3. Refresh Token 实现细节与文档 §8.6.2 不符

文档 §8.6.2 说 refresh「校验 refreshToken(存 `refresh_tokens.token_hash`,与 userId 绑定)」。代码用 `token_hash` SHA-256 存储 ✅,但实现是用 `FOR UPDATE` 锁 + 作废旧 + 发新的(完整 rotation)。文档应补充 rotation 语义。另外文档 §6.3.2 说 `revoked_at`「登出/改密/禁用时置空或删除」——**这是文档错**(我在第 1 次文档审查 C4 已指出),代码 L116 `SET revoked_at = NOW()` 是对的(置非空 = 已撤销)。

### C4. `updateUserRole` 降级 VIEWER 时的额外约束未在文档体现

`AuthService.updateUserRole` L178-180:降为 VIEWER 前会 `requireNoKbManagementRole`,即若该用户仍是某 KB 的 OWNER/EDITOR,拒绝降级。这是个**很好的数据一致性保护**(避免孤儿管理角色),但文档 §8.2.1、§9.2 完全没提这个约束。API 使用者会遇到"降级失败"却不知原因。

### C5. `listMine` 与 `listManageable` 两个接口文档都没写

代码有 `GET /api/kbs`(我加入的库)和 `GET /api/kbs/manageable`(我能管理的库)两个接口,文档 §9.2 只有一个 `GET /kb`。`listManageable` 的 EDITOR 分支(L322-333)还体现了 I1 的 EDITOR 权限。

### C6. Demo 用户配置与文档 §8.6.1 不符

文档 §8.6.1:「Demo 环境 `application-dev.yml` 可预置 `admin/admin`」。代码:

- 用 `application-local.yml` / `application-db.yml`(无 `application-dev.yml`);
- 用户名 `demo/demo` 非 `admin/admin`;
- 用 `@ConditionalOnProperty`(默认 false),而非文档说的"dev profile 自动启用";
- 角色是 ADMIN(L27)。

实现比文档更安全(默认关闭 + BCrypt),但文档描述需更新。

## 🟢 优化建议

### O1. `KnowledgeBaseService` 权限校验重复查询 DB

`requireKbMember`(L282-306)和 `requireKbOwnerOrAdmin`(L352-376)每次都单独查 `kb_members`。在一个请求中可能多次调用(如 `delete` 先校验再操作),建议抽取 `memberRole(kbId, userId)` 复用,减少 DB 往返。

### O2. `CurrentUser` record 缺少 `@empty` 权限注解

`CurrentUserPrincipal` 实现了 `UserDetails`,但 `CurrentUser` 只是个 record。`@PreAuthorize("hasRole('ADMIN')")` 这类声明式权限完全没用到——全是手写 `requireAdmin()`。可以引入方法注解式授权,减少重复代码。但 MVP 手写也可接受。

### O3. JWT 实现自研而非用 jjwt/spring-security-oauth2

`JwtService` 自研 HMAC-SHA256 JWT(L83-91)。实现是**正确的**(常量时间比较 L101-105、强制 secret ≥32 字符 L107-112 都到位),但:

- 不支持 token 刷新前的剩余时间检查;
- 没有 `nbf`/`iss`/`aud` 等标准 claim;
- 自研密码学代码维护成本高。

MVP 可接受,但文档 §5.1 选型表应注明"JWT 自研实现",面试时要能解释为什么不用 jjwt。

### O4. `SecurityConfig` 暴露 `/actuator/prometheus` 和 `/actuator/metrics` 无需认证

`SecurityConfig.java:47-48` 把 `/actuator/prometheus`、`/actuator/metrics` 设为 `permitAll`。Prometheus 抓取通常在内网,但 `/metrics` 暴露了应用内部指标(QaTrace 计数、线程池等),无认证可能泄露架构信息。生产建议加 IP 白名单或 basic auth。

### O5. `KnowledgeBaseResponse` 的 `memberRole` 参数语义不清

`mapKnowledgeBase` L485-486 返回 `memberRole` 和两个硬编码 `null`/`false`。建议用 builder 或明确字段名,避免后续维护时不知道第 7、8 个参数是什么。

------

## 第 2 次审查总结

| 维度           | 结论                                                         |
| :------------- | :----------------------------------------------------------- |
| **认证(JWT)**  | 自研 JWT 实现质量**高**(常量时间比较、secret 强度校验、token_version 失效机制都到位)。Access TTL=15min 合理,refresh rotation 已实现(优于文档)。主要问题是密码 4 位过弱 + 无登录限流。 |
| **授权(RBAC)** | KB 成员校验扎实(`requireKbMember`/`requireKbOwnerOrAdmin`/`requireAnotherOwner` 留住 OWNER 都对)。**但全局 EDITOR 角色语义与文档严重不符**(I1),且文档检索层安全核心"kbId 服务端绑定"在 KB 服务中无体现(S1,待第 4 次确认)。 |
| **文档对齐度** | **§9.2 API 路由整节作废**(C1),登录响应字段不符(C2),权限矩阵缺 EDITOR(I1)。 |
| **代码质量**   | KB 删除的手写级联(I3)、DB 重复查询(O1)是主要可改进点;`requireNoKbManagementRole`(L252)这种防孤儿角色设计是亮点。 |

**本批次最紧急的三件事:**

1. 🔴 **第 4 次审查必须确认 chat 链路的 kbId 来源**(S1)——若 chat 直接用请求体/路径 kbId 检索,文档承诺的"检索层 kbId 绑定"就是空头支票;
2. 🟠 **决定 EDITOR 角色的最终语义**(I1)——要么文档补 EDITOR 列并解释"可建库",要么代码去掉 EDITOR 建库权限;
3. 🟠 **补登录限流 + 提高密码最小长度**(I4/I5),或至少在文档明确 demo 环境边界。

# 第 3 次审查报告:入库 + 索引

审查对象:`document/`(26 文件,1379 行集中在 DocumentService)+ `index/`(9 文件)。共 ~3838 行。 对照文档:§4.4.1、§6.2、§7.2、§7.3、§11.3、§13.3。

发现分级:**🔴 严重 / 🟠 重要 / 🟡 一致性 / 🟢 优化**。

## 🔴 严重问题

### S1. 切块器与文档 §7.2.2 设计完全脱节:无 token 级、无 overlap

文档 §7.2.2 是**花了大篇幅强调的核心设计**:

- 要求 `OverlapTokenSplitter`(token 级滑动窗口,overlap 80);
- 明确指出 Spring AI `TokenTextSplitter` 不支持 overlap,要自研;
- W2 校验「相邻 chunk token 级重叠」,W5 golden set 对比 Recall@5 差距 ≥3%。

**代码实际**(`SimpleTextChunker.java`):

- 全程**字符长度**(`PARENT_CHUNK_SIZE = 1_200` 字符,`CHILD_CHUNK_SIZE = 400` 字符),不是 token;
- **完全没有 overlap**——`splitRanges()`/`splitOversizedRange()` 的相邻块边界是 `start = end`(L123),纯硬切,无滑动窗口;
- 无任何 token 概念(grep `overlap|token` 无匹配)。

**后果:**

1. 中文场景下"400 字符"与"400 token"差异巨大(中文 1 字符 ≈ 1-2 token),实际 chunk 容量远超设计;
2. **召回质量受损**:相邻 chunk 间无重叠,跨块边界的问题(如一个完整条款被切成两半)无法召回完整语义;
3. 文档 §7.2.2 整节描述与代码不符,面试官若追问"overlap 怎么实现的",无法回答。

**值得肯定的是**:`SimpleTextChunker` 的语义切分(`semanticUnits`,L128-152)按段落/句号/中文句号(`。！？；`)切分,**比纯字符硬切好**,且表格/列表保留换行(L243-245)。这是工程上合理的简化,但**不是文档承诺的方案**。

**建议:** 二选一——要么改文档(说明 MVP 用字符级语义切分,overlap 列入 v1.1),要么补 overlap 实现。

### S2. `publish` 无幂等防护,连点会写多条 Outbox 事件

`DocumentService.createPublishEvent()`(L534-573):

![img](./material-icons/java.svg)java

```
DocumentResponse document = getDocumentForMember(docId, user.id());
requireKbWriteAccess(document.kbId(), user);
requireLatestIngestReady(docId);      // 只检查 ingest READY,不检查是否已 PUBLISHED
// 直接 UPDATE status=PUBLISHED + INSERT outbox event
```

**没有任何"已是 PUBLISHED 则跳过"的检查。** 这是我在第 1 次文档审查时(I9)预警的问题,代码实锤。后果:

- 用户连点两次「发布」→ 写两条 PENDING Outbox 事件;
- 两个 `IndexEventConsumer`(虽然 MVP 单实例,但 Poller 的 `MAX_EVENTS_PER_TICK=5` 会连续消费)消费两条事件;
- 每条事件都执行 `delete(docId) + add`(VectorIndexService L78-107),虽然单事务保护,但第二条事件基于第一条已删除的状态重新 add——**重复 embedding 同一批 chunk,浪费 DashScope 配额 + 重复写向量**(最后因 `chunk_children.UNIQUE(doc_id, doc_version, chunk_index)` 防住重复行,但向量表 `vector_store` 无唯一约束,会产生重复向量行)。

更隐蔽的问题:**ARCHIVED 文档也能再次 publish**(L534 不检查 status)。文档 §6.2 状态机说「ARCHIVED → PUBLISHED: 重新发布」是允许的,但代码没区分"首次发布"和"重新发布",且重新发布不会清理已删除的向量(已删,所以 OK),逻辑上可工作,但 Outbox 事件会累积。

**建议:** publish 前检查 `if (status == 'PUBLISHED' && index_status == 'SYNCED') return current;`,并对 `document_publish_events (doc_id, doc_version, event_type)` 加唯一约束(同一文档同版本只允许一个 PENDING)。

### S3. 文档 §7.3.2 承诺的"`@Retryable` 指数退避解析重试"完全没实现

文档 §7.3.1:

> 重试 | FAILED 可手动 retry;`@Retryable`(需 `@EnableRetry`)解析超时最多 3 次,指数退避 1s→2s→4s;超时由 Resilience4j `@Timeout(60s)` 触发

**代码实际:**

- `parseAndPersistChunks`/`parseAndPersistStoredSource`(L744-777)异常直接 `markIngestFailed`,**无自动重试**;
- 只有手动 `retryLatestIngestTask`(L307-330),且要求 status=FAILED;
- 无 `@Retryable`、无 `@Timeout`、无 Resilience4j 在解析链路的使用(grep 证实)。

`@EnableRetry` 在 `KnowSourceApplication` 也没开(L8-10 只有 `@EnableAsync` + `@EnableScheduling`)。即**解析失败 = 必须人工干预**,与文档承诺的"自动指数退避 3 次"完全不符。结合 S2,发布索引失败虽有 Outbox 重试(那部分做得好,见亮点),但解析阶段失败没有任何自动恢复。

## 🟠 重要问题

### I1. replace/replaceUpload 重建期间文档不可检索,且与文档 §7.3.2 边界声明冲突

`replaceDocumentAndCreateTask`(L625-682):

![img](./material-icons/java.svg)java

```
vectorIndexService.deleteDocumentVectors(existing.id());  // 先删向量
clearChunksForVersion(...);
UPDATE documents SET status='DRAFT', index_status='NONE', published_at=NULL ...
```

即:**已发布的文档替换内容时,立即删除所有向量并回到 DRAFT**,直到用户重新 publish + 索引 SYNCED 才恢复检索。这期间文档完全不可检索。

文档 §7.3.2 明确说:

> MVP 面试 Demo 不实现无中断替换。默认流程是「**下架旧文档 → 上传/解析新版本 → 发布**」

但代码提供了 `replace`/`replaceUpload` 接口(DocumentController L46-59),**绕过了"先下架"的语义**,直接在 PUBLISHED 状态上原地替换并清空向量。这与文档描述的流程不符,且 replace 接口无任何"文档当前必须是 ARCHIVED/DRAFT"的前置检查——**用户对一个正在被问答的文档执行 replace,会瞬间让它消失**。

**建议:** 要么 replace 前置检查 `status != PUBLISHED`,要么文档明确 replace 的语义(原地降级 + 清向量)。

### I2. `chunk_children` 主键用序号拼接,replace 时主键冲突隐患

`persistChunks`(L888, L909):

![img](./material-icons/java.svg)java

```
parentChunkId = docId + "-v" + docVersion + "-p-" + parentIndex;
id = docId + "-v" + docVersion + "-c-" + childChunkIndex;
```

主键带 `docVersion`,所以不同版本不冲突 ✅。但 `replaceDocumentAndCreateTask` 先 `clearChunksForVersion(nextVersion)`(L634)再写入——**注意是清 nextVersion 不是 currentVersion**。如果 nextVersion 的 chunks 之前因某次失败残留,清理是对的;但如果是首次 replace(version 1→2),clearChunksForVersion(docId, 2) 删的是不存在的数据,无害。

**真正的隐患:** `replace` 接口允许对同一文档**并发**调用(无锁),两个请求都算出 `nextVersion = current + 1`,都写 v2 的 chunks——`UNIQUE(doc_id, doc_version, chunk_index)` 会拦住第二个,但第一个请求的 ingestTask 和第二个的 ingestTask 会冲突。MVP 单用户低概率,但缺乏防护。

### I3. OCR 已实现但文档标为 Out of Scope(呼应第 1 次 I4)

`LocalOcrService` + `PlainTextDocumentTextExtractor.java:94-97` 真实调用了 tesseract:

![img](./material-icons/java.svg)java

```
if (localOcrService.enabled()) {
    var ocrText = localOcrService.extractText(image);
    ...
}
```

- 文档 §2.4:「Graph RAG、多模态、Agent 工具调用 → 第 15 章演进」(OCR 属多模态,Out of Scope);
- 文档 §7.2.3:「进阶:Tabula-java 结构化、OCR 预处理」(暗示 v2);
- 文档 §15.2:「OCR 预处理路由」列在未来演进。

**代码已落地 OCR**(默认 `enabled=false`,但能力存在),且 `ingest_tasks` 有 `ocr_required_page_count`/`ocr_applied_page_count` 列(V6 迁移)做质量统计。这是第 1 次审查 I4 的进一步实锤:**代码至少有 3 个功能(混合检索、OCR、质量报告)超出文档定义的 MVP 边界**。

### I4. `openSourcePreview` 用 `sourceKey` 作为查询参数,存在未授权访问风险

`DocumentController.sourcePreview`(L109-113):

![img](./material-icons/java.svg)java

```
@GetMapping("/documents/source-preview")
public ResponseEntity<...> sourcePreview(@RequestParam String sourceKey) ...
```

`sourceKey` 是客户端直接传入的。`DocumentService.openSourcePreview`(L451-532)内部确实做了 `JOIN kb_members` 校验(L495, L520)——**这一点是对的,有成员校验**。但:

- `sourceKey` 是路径式字符串(`local://{kbId}/{docId}/v1/file.pdf`),**可枚举**:攻击者拿到自己的一个有效 sourceKey,可猜测其他 docId 的 key 并尝试访问;
- 成员校验靠 `oss_key` 反查 documents 再 JOIN members,逻辑正确,但**依赖 oss_key 全局唯一**——若两个文档因 bug 共享 oss_key,校验会绕过。

风险等级中等(有成员校验兜底),但 previewUrl 设计暴露了内部存储路径结构。

### I5. 删除文档时 OSS/local 文件清理是 best-effort,且 replace 时不清理旧版本文件

`deleteSourceQuietly`(L1021-1031):删除文档时 `catch (IOException ignored)` 静默吞掉。注释说"DB row is source of truth",可接受。但:

- **replace 时旧版本文件不清理**:`storeUploadedSource` 每次写 `v{nextVersion}` 新路径,旧 `v1` 文件永久残留;
- **archive(下架)不删源文件**(L369-394 只删向量,不动 oss_key)——这是对的(下架≠删除),但文档没说明积累;
- 配合第 2 次审查 I3(KB 删除不清理 OSS),**存储泄漏是系统性的**:delete doc 清理 best-effort、replace 不清理、delete KB 不清理。

### I6. `ingestExecutor` 用 `AsyncTaskExecutor.execute()` 而非 `submit()`,异常行为不同

`DocumentService` 多处 `ingestExecutor.execute(...)`(L118, L139, L161, L182, L322)。`AsyncTaskExecutor.execute()` 返回 void,异步任务内的异常被各自的 `try/catch` 包住(parse 方法内 catch 后 markIngestFailed)——**这个设计是对的**。但外层 `catch (TaskRejectedException)`(L119)只捕获"提交被拒",提交后的异步异常已在内部处理。

**真正的问题:** 如果 `markIngestFailed` 本身(L811-843,一个事务)抛异常(如 DB 连接断开),异步线程里这个异常会静默丢失(无 catch),文档状态停在 PARSING 永远不变成 FAILED——**即"僵死任务"**。文档 §11.3.1 承诺「PARSING > 30min → FAILED」的僵死回收,**代码里没有这个定时任务**(grep 无 PARSING 超时回收逻辑,只有 Outbox 的 SYNCING 回收 L110-133)。解析僵死任务不会被自动回收。

## 🟡 一致性问题

### C1. `DocumentService` 大量 SQL 重复(DOCUMENT_SELECT_COLUMNS 5 处复用但 LATERAL 子查询复制粘贴)

`getDocument`、`getDocumentForMember`(ADMIN/非 ADMIN 两版)、`openSourcePreview`(ADMIN/非 ADMIN 两版)、`createDocumentAndTask`、`replaceDocumentAndCreateTask` 共 6 处大段重复 SQL。维护成本极高(改一个字段要改 6 处)。建议抽取 SQL 构建器或 private 方法。

### C2. 文档 §6.2.1「下架流程 ARCHIVE Outbox」未实现,代码直接删向量

文档 §6.2.1:

> 下架流程:事务内 status=ARCHIVED、index_status=NONE → **Outbox ARCHIVE** → 消费者 vectorStore.delete(docId)

代码 `archive`(L369-394):事务内**直接** `vectorIndexService.deleteDocumentVectors(docId)`,**不写 Outbox ARCHIVE 事件**。即下架是同步删向量,而非文档承诺的"Outbox 驱动"。功能上等价(都删了向量),但:

- 失去了 Outbox 的重试能力(若删向量时 DB 异常,archive 整个事务回滚,文档停留在 PUBLISHED+SYNCED——**下架失败但用户以为成功了**);
- 与文档「发布/下架通过 Outbox 驱动索引副作用」的核心设计原则(§4.1 原则 8)不符。

### C3. 文档 §7.2.2 的 Parent-Child 参数与代码一致,但单位不同

| 参数             | 文档                            | 代码          |
| :--------------- | :------------------------------ | :------------ |
| parent chunkSize | 1200 **token**                  | 1200 **字符** |
| child chunkSize  | 400 **token**                   | 400 **字符**  |
| overlap          | parent 200 / child 80 **token** | **无**        |

数字一致,单位从 token 变成字符,overlap 消失。文档 §7.2.2 表格需修正。

### C4. `document_publish_events` 缺少文档 §6.3.8 承诺的部分字段使用

文档 §6.3.8 表定义了 `locked_by`(`FOR UPDATE SKIP LOCKED` 拉取时写 hostname+pid)。代码 `claimNextEvent`(L161)写死 `locked_by = "knowsource-local"`——**不支持多实例标识**。文档 §11.3.2 说「`locked_by` = instanceId」,代码是常量。MVP 单实例可工作,但文档的"多 Worker"设计未落地。

### C5. DashScope Embedding 走自研 HTTP 网关,绕开 Spring AI EmbeddingModel

`DashScopeEmbeddingGateway`(134 行)直接 HTTP 调 DashScope `/v1/embeddings`。文档 §7.3.2 说用 `DashScopeEmbeddingModel` + `TokenCountBatchingStrategy`。代码的批量逻辑是 `embedInBatches`(VectorIndexService L116-130)手动按 `embeddingBatchSize` 切片——**不是 Spring AI 的 batching strategy**。功能等价,但文档描述与实现不符。

## 🟢 亮点(代码优于文档的地方)

### G1. Outbox 状态机实现质量很高 ⭐

`DocumentIndexOutboxService` 是本批次最优秀的代码:

- `claimNextEvent`(L135-177):`FOR UPDATE SKIP LOCKED` 领取 + 同事务标记 SYNCING + 更新 documents,**短事务领取**与文档 §11.3.2 完全一致;
- `recoverStaleSyncingEvents`(L110-133):用 `WITH recovered AS (UPDATE ... RETURNING)` 一条 SQL 同时回收僵死事件 + 更新 documents,**比文档描述更优雅**;
- `markFailed`(L206-244):`attempt_count++` + 指数退避 `retryDelaySeconds`(L301-310,带 `Math.multiplyExact` 防溢出);
- `failureMessage`(L274-299):遍历完整 cause 链而非只取 root cause,带中文注释解释为什么——**这是经过真实排障打磨的代码**。

这部分**完全实现了文档 §11.3.2 的承诺**,甚至更严谨。

### G2. VectorIndexService 单事务保护 delete+add ⭐

第 1 次审查已指出:文档 §7.3.2 描述"事务外 delete→add"有清空风险,代码用 `transactionTemplate.execute`(L78-107)包裹,**解决了文档设计的安全隐患**。

### G3. 切块的语义感知切分

虽无 overlap(S1),但 `semanticUnits` 按中英文标点、段落、表格换行切分,比朴素字符切分好,且 `findBreakPoint`(L154-171)在硬切时优先找标点边界。

### G4. 质量报告设计扎实

V6 迁移的 10 个质量列 + `quality_report` JSONB,配合 `ExtractionQualityReport`,提供了完整的入库质量可观测性(空页/失败页/OCR 统计)。这是文档完全没写、但代码做得很好的部分。

------

## 第 3 次审查总结

| 维度                | 结论                                                         |
| :------------------ | :----------------------------------------------------------- |
| **入库(解析+切块)** | Tika 集成正确(含 OCR 超纲实现),但**切块与文档 §7.2.2 严重脱节**(字符级、无 overlap、无 token)。解析失败无自动重试(S3)、无僵死回收(I6)。 |
| **索引(Outbox)**    | **本批次最大亮点**。Outbox 状态机、SKIP LOCKED、僵死回收、指数退避都高质量实现(G1),单事务保护 delete+add 优于文档(G2)。 |
| **文档对齐度**      | §7.2.2 切块(S1)、§7.3.1 解析重试(S3)、§6.2.1 下架 Outbox(C2)三处设计未落地;OCR/质量报告超出 MVP 边界(I3/G4)。 |
| **代码质量**        | DocumentService 1379 行单体 + 6 处 SQL 重复(C1)是主要可维护性问题;Outbox/VectorIndex 设计精良。 |

**本批次最紧急的三件事:**

1. 🔴 **决定切块策略走向**(S1)——补 overlap 实现,或改文档说明 MVP 用语义切分;
2. 🔴 **补 publish 幂等**(S2)——加 `(doc_id, doc_version, event_type)` 唯一约束 + 状态前置检查;
3. 🟠 **补解析僵死回收**(I6)——参照 Outbox 的 `recoverStaleSyncingEvents`,对 `PARSING > 30min` 的 ingest_tasks 做定时回收。

# 第 4 次审查报告:问答与检索(RAG 核心)

审查对象:`chat/` 38 个文件,重点覆盖 `ChatService`、`ChatSessionService`、`RetrievalService`、`VectorSearchService`、`LexicalRetriever`、Rerank、SSE、QaTrace。对照文档:§4.4.2、§7.4-7.7、§11.1。

发现分级:**🔴 严重 / 🟠 重要 / 🟡 一致性 / 🟢 亮点**。

## 🔴 严重问题

### S1. V8 混合检索已经接入生产问答链路,文档的 MVP 边界判断失效

第 1 次审查留下的悬念已经确认:混合检索不是“迁移存在但未接入”,而是**默认生产路径**。

证据:

- `application.yml:123-132`: `knowsource.retrieval.mode` 默认是 `hybrid`;
- `RetrievalService.java:33-39`: 构造器读取默认模式、vector/lexical topK、RRF 参数;
- `RetrievalService.java:53-87`: `search()` 按模式同时调用 vector 与 lexical,再 `fusionService.fuse(...)`、`mergeAcrossQueries(...)`、最终 rerank;
- `LexicalRetriever.java:32-65`: 基于 `vector_store.content_tsv` + `plainto_tsquery('simple', ...)` 做 FTS 召回;
- `ChatControllerTest.java:853-895`: 明确验证 vector miss、hybrid 通过 lexical 命中,且 QaTrace 记录 `retrievalSource=LEXICAL`。

这意味着文档 §7.4.5 写“混合检索 v1.1 才做”、§2.4 把相关能力放到 Out of Scope 已经不成立。当前真实架构是:

> 默认 Hybrid Retrieval = vector candidates + lexical FTS candidates + RRF fusion + rerank。

**建议:** 文档把混合检索从 Out of Scope 移到 MVP/当前实现,补充 V8 migration、`TextTokenizer`、RRF、`retrievalMode` API 参数和 QaTrace/source 字段。

### S2. 多轮问答“检索用改写问题,生成仍用原始问题”,CompressionQuery 没有完整贯穿生成链路

`ChatService.prepareContext()` 会正确做多轮改写:

- `QueryRewriteService.java:40-49`: modular 时返回 `rewritten` 与 `retrievalQueries`;
- `ChatService.java:160-166`: 检索使用 `rewriteResult.retrievalQueries()`;
- `ChatService.java:172-183`: `ChatContext` 里保存了 original `question` 和 rewritten query。

但生成阶段:

- `ChatService.java:239-241`: `answerGenerator.generate(context.question(), context.sources())`;
- `ChatService.java:208-211`: SSE 也把 `context.question()` 传给 streaming generator;
- `SpringAiAnswerGenerator.java:114-124`: prompt 只有“上下文 + 用户问题”,没有会话历史,也没有 `rewrittenQuery`。

结果是:检索可以靠“上一轮 + 当前追问”的改写命中正确文档,但 LLM 看到的仍可能是“它的审批流程是什么?”这类缺少指代对象的问题。测试也只断言 `rewrittenQuery` 和 sources(`ChatControllerTest.java:324-339`),没有断言最终 answer 是否基于改写问题生成。

**建议:** 生成 prompt 使用 `rewriteResult.query()` 作为“检索/回答问题”,同时保留原始用户问题用于 trace 展示;或把最近会话历史也注入生成 prompt。否则 Modular RAG 的“CompressionQuery 解决多轮指代”只解决了召回,没有完整解决回答。

### S3. `hasLexicalEvidence` 在向量召回之后加了强词重叠硬门槛,可能误拒语义召回结果

`ChatService.java:170` 的拒答条件是:

```java
if (chunks.isEmpty() || !hasLexicalEvidence(rewriteResult.retrievalQueries(), chunks)) ...
```

`hasLexicalEvidence()` 又要求 query strong terms 与 chunk strong terms 至少有一个重叠(`ChatService.java:394-408`),且没有 strong terms 时直接返回 false(`ChatService.java:399-400`)。

这不是文档 §7.4/§7.6 描述的“向量相似度阈值 + 空上下文拒答”,而是额外加了一层**词法证据门**。它能压住“公司股票代码是多少”这类误召回,但代价是:

- 纯语义同义表达可能被高相似度向量召回后仍拒答;
- 短问题、代词型追问、术语别名问题更容易因为 strong terms 不重叠而误拒;
- Hybrid/lexical 命中会更容易过门,但这让“向量召回”的价值被词法门二次限制。

**建议:** 文档必须补“词法证据门”的拒答策略;代码上建议把它做成可配置开关/阈值,并在 golden set 加同义改写用例,避免 Recall@5 被这个门槛压低。

## 🟠 重要问题

### I1. SSE 的 `chatExecutor.execute()` 没有提交失败保护

`ChatService.stream()` 创建 `SseEmitter` 后直接 `taskExecutor.execute(task)`(`ChatService.java:128-138`)。如果线程池满了抛 `TaskRejectedException`:

- 代码没有 catch;
- emitter 可能已经创建但不会发送结构化 `error/done`;
- 与第 1 次审查 I1 的“线程池缺 CallerRunsPolicy”叠加后,高负载时用户看到的更可能是 500 或悬空连接,而不是可审计的拒绝。

**建议:** `execute` 外层 catch `TaskRejectedException`,发送 `event:error` + `complete`;并补线程池拒绝策略。

### I2. QaTrace 的 `token_usage` 永远是 `{}`,文档 §11.1 的 token 可观测性未落地

`QaTraceService.record()` 写入 `token_usage` 时固定传 `"{}"`(`QaTraceService.java:67`),`ChatService.traceRecord()` 也没有任何 token 统计来源(`ChatService.java:470-486`)。

文档 §11.1/§11.2 把 QaTrace 定位为 query、召回、耗时、token 的全链路追踪。当前耗时和 sources 已落地,但 token 维度是空壳。面试时如果展示 QaTrace 表,这一列会被追问。

**建议:** 如果短期拿不到 DashScope usage,文档改成“token_usage 预留字段”;如果要兑现,非流式从 provider response usage 取值,SSE 在最终 chunk 或 provider 统计中补。

### I3. Rerank topN 用的是最终 `topK`,不是文档里的“粗排 15 → 精排 5”的明确语义

文档多处写“粗排 15 → qwen3-rerank → top 5”。代码实际:

- `RetrievalService.java:83-86`: candidates 最多 15,传给 `documentReranker.rerank(..., topK)`;
- `DashScopeDocumentReranker.java:38-42`: provider `top_n = Math.min(topK, chunks.size())`;
- `ChatRequest.topK` 最大可到 15(`RetrievalService.java:113-120`)。

默认 topK=5 时符合文档;但如果客户端传 `topK=10/15`,rerank provider 会返回 10/15,不是固定 top5。这是合理功能,但文档应从“固定 top5”改成“默认 top5,请求可在 1..15 内调整”。

## 🟡 一致性问题

### C1. `kbId` 服务端绑定的实际实现比第 2 次预警更好,但文档表述需要更新

第 2 次审查担心 chat 直接信任请求体 `kbId`。第 4 次确认:

- 请求体 `ChatRequest` 没有 `kbId` 字段(`ChatRequest.java:3`);
- API 路由是 `/api/kbs/{kbId}/chat`(`ChatController.java:18-41`);
- 新会话用路径 `kbId` 创建(`ChatSessionService.java:46-55`);
- 已有会话要求 `session_id + user_id + kb_id` 同时匹配(`ChatSessionService.java:200-216`)。

所以这里不是严重漏洞。当前实现的安全模型是“路径 kbId + membership 校验 + session 所属 KB 校验”,不是文档说的“从 ChatSession.kbId 取,请求 kbId 仅一致性校验”。功能上安全,但文档应按代码改写。

### C2. 生产 RAG 没有使用 Spring AI Advisor 链,而是手写 orchestration

文档 §4.2、§7.4 多次使用 `RetrievalAugmentationAdvisor`、`VectorStoreDocumentRetriever`、`DocumentPostProcessor` 等 Spring AI Advisor 叙事。代码实际生产路径是:

`ChatService` → `QueryRewriteService` → `RetrievalService` → `VectorSearchService`/`LexicalRetriever` → `DocumentReranker` → `SpringAiAnswerGenerator`

也就是说 W1 Spike 验证过 Spring AI Advisor,但生产问答选择了 SQL-first + service orchestration。这和第 1 次的 `KnowSourceVectorStore` 问题同源:代码路线更可控,文档路线还停在框架式设计。

### C3. SourceCitation 已扩展 retrieval/fusion 字段,文档 `sources[]` 结构过旧

`ChatService.toSource()` 输出的不只是 `docId/title/page/snippet/score`,还包括:

- `retrievalSource`
- `vectorRank`
- `lexicalRank`
- `vectorScore`
- `lexicalScore`
- `fusionScore`

见 `ChatService.java:313-329`。这些字段对解释 Hybrid/RRF 很有价值,但文档 §7.7 和 API 示例没覆盖。

## 🟢 亮点

### G1. 检索过滤比文档 OR Filter 更稳

`VectorSearchService.java:107-120` 和 `LexicalRetriever.java:47-62` 都 JOIN `documents`,并要求:

- `d.version = vs.doc_version`
- `d.kb_id = vs.kb_id`
- `d.status = 'PUBLISHED'`
- `d.index_status = 'SYNCED'`
- `vs.status = 'published'`

这比文档中每次构造 `(doc_id, doc_version)` OR Filter 更直接,也避免了大 KB 下长 OR 表达式的 SQL 计划问题。它没有实现“已同步版本白名单缓存”的叙事,但生产可读性和安全边界更好。

### G2. SSE 早推 sources 已落地

`ChatService.streamContext()` 先发送 `event:sources`(`ChatService.java:195-197`),再进入 token streaming。前端可先展示证据面板,这与文档 §7.7 的“PostProcessor 早推 sources”目标一致,只是实现点从 Advisor/PostProcessor 转成了 service 层。

### G3. Provider 降级链路比较完整

- query embedding 失败返回空候选(`VectorSearchService.java:86-90`);
- lexical/vector 单路失败在 Hybrid 下吞掉,另一侧继续(`RetrievalService.java:97-110`);
- rerank provider 不可用或失败时走 lexical fallback(`DashScopeDocumentReranker.java:33-45`);
- chat provider 失败时返回稳定忙碌文案(`ChatService.java:239-244`)。

这部分比文档描述更像真实生产兜底。

------

## 第 4 次审查总结

| 维度             | 结论                                                         |
| :--------------- | :----------------------------------------------------------- |
| **RAG 路由**     | auto 路由已落地:单轮 naive、多轮 modular(`RagProfileRouter.java:8-17`)。 |
| **检索**         | 默认 Hybrid 已接入生产链路,文档 Out of Scope 结论错误。SQL JOIN 过滤 published+SYNCED+version,比文档 OR Filter 更稳。 |
| **多轮**         | CompressionQuery 只完整作用到召回,生成阶段仍用原始问题,这是 Modular 链路的主要缺口。 |
| **SSE/QaTrace**  | sources 早推、done/trace 落地;但 token_usage 空壳,SSE 提交失败缺保护。 |
| **文档对齐度**   | Spring AI Advisor、KnowSourceVectorStore、Hybrid 边界、sources 字段结构都需要重写。 |

**本批次最紧急的三件事:**

1. 🔴 **更新文档:Hybrid 已是默认生产检索模式**(S1);
2. 🔴 **让生成阶段使用 rewritten query 或注入会话历史**(S2);
3. 🟠 **补 SSE 提交失败保护 + QaTrace token_usage 真实口径**(I1/I2)。

# 第 5 次审查报告:评测 + 前端 + 测试 + 部署(收尾)

审查对象:`eval/`、`src/test/`、`frontend/`、`docker-compose.yml`、前后端配置。对照文档:§10、§12、§13。

发现分级:**🔴 严重 / 🟠 重要 / 🟡 一致性 / 🟢 亮点**。

## 🔴 严重问题

### S1. 前端 SSE 客户端丢弃后端 `event:error`,LLM 未配置时会出现“正在生成...”卡住/无错误态

后端在流式生成器不可用时会发送结构化错误:

- `ChatService.java:259-268`: `event:error` + `ChatStreamError` + `emitter.complete()`。

但前端只处理 3 种事件:

- `chatStream.ts:66-72`: `sources`、`token`、`done`。

`event:error` 被 `dispatchFrame()` 静默忽略。`ChatTab.send()` 又只在 `onDone` 里设置 `qaTraceId/sessionId/final answer`(`ChatTab.vue:339-368`)。因此当后端发 `event:error` 并正常结束 HTTP 200 stream 时,前端不会进入 catch,也不会收到 done,助手气泡可能停在初始的“正在生成...”,用户看不到后端错误。

**建议:** `chatStream.ts` 增加 `onError` 或直接 `throw new Error(parsed.message)`,并在 `ChatTab` catch 中替换 assistant 消息。后端也可以在 error 后补一个 `done(refused=true)` 以简化客户端状态机。

### S2. EvalRunner 的 `Recall@5` 实际等于“期望文档标题命中率”,指标口径偏乐观

`EvalRunnerService.toCaseResponse()` 只看 `sourceTitles.contains(expectedDocTitle)`(`EvalRunnerService.java:189-197`),`summarize()` 又把 `citationRate` 同时填给 `recallAt5` 和 `citationHitRate`(`EvalRunnerService.java:213-223`)。

测试版 `EvalRunnerTest` 也同样如此:

- `GoldenCase.expectedKeywords` 被定义(`EvalRunnerTest.java:300-307`),但没有参与通过判定;
- `EvalSummary.from()` 第 366-367 行同样让 recall 与 citation hit 完全相同。

这意味着报告里的 `Recall@5=100%` 不是严格的“期望 chunk 出现在 top5”,也不是“答案包含 expectedKeywords”,而是“至少一个来源文档标题匹配”。对面试叙事来说容易被质疑。

**建议:** 文档和报告把当前指标命名为 `ExpectedDocHit@5`;若继续叫 Recall@5,需要 golden case 增加 expected chunk/section/keyword,并检查 sources snippet 或 retrieved chunk id。

### S3. `docker-compose.yml` 仍只有 PostgreSQL,文档 §13 的“一键部署 app”不成立

实际 `docker-compose.yml` 只有 `postgres` 服务和 volume,没有:

- Spring Boot app service;
- frontend build/serve service;
- app healthcheck;
- `.env` 注入 app 的配置;
- depends_on postgres health。

这与文档 §13.1/§13.2 的 `postgres + app` compose 示例、以及“Docker Compose 15 分钟内可启动完整环境”的目标不一致。第 1 次审查 C5 仍然成立。

**建议:** 要么补 compose 的 `app`(必要时包含 frontend build 或静态资源服务),要么把文档改成“Compose 仅提供数据库,应用本地 Maven/Vite 启动”。

## 🟠 重要问题

### I1. 前端没有暴露 `profile/topK/retrievalMode` 控件,与文档和后端能力不一致

后端支持:

- `ChatRequest(question, topK, profile, sessionId, retrievalMode)`(`ChatRequest.java:3`);
- `profile=auto/naive/modular`;
- `retrievalMode=vector/lexical/hybrid`;
- `topK=1..15`。

前端类型也声明了这些字段(`frontend/src/types/api.ts`),但 `ChatTab.vue` 实际写死:

- `defaultRagProfile = 'auto'`(`ChatTab.vue:45`);
- 发送请求只传 `question/profile/sessionId`(`ChatTab.vue:321-327`);
- 没有 topK、profile、retrievalMode 选择器。

这让面试 demo 无法直接展示 naive vs modular、vector vs hybrid、topK 调参。项目总结中写“profile selector、topK numeric input or slider”也与当前代码不符。

**建议:** 在 composer 附近加紧凑控件: profile segmented control、retrievalMode select、topK slider/input。默认仍保持 auto/hybrid/5。

### I2. 评测 API 每次运行都会创建新知识库,没有清理策略

`EvalRunnerService.runGoldenSet()` 每次:

- 创建新 KB(`EvalRunnerService.java:66-68`);
- seed 四个文档并 publish(`EvalRunnerService.java:69`);
- 写 report 后直接返回,没有标记或清理。

这对测试没问题,但作为后台 API 运行多次会留下大量“评测知识库”、文档、向量、QaTrace。前端 Evaluation tab 鼓励管理员反复点“运行评测”,会持续污染演示数据。

**建议:** 固定复用一个 `EVAL` KB,或在 run 前清理上一轮评测 KB;至少加 `metadata`/命名前缀清理接口和文档说明。

### I3. 运行时写 `docs/eval/report.md`,容器/生产环境可能不可写

`EvalRunnerService` 把报告路径写死到源码仓库路径:

- `REPORT = Path.of("docs/eval/report.md")`(`EvalRunnerService.java:32-34`);
- `Files.writeString(REPORT, report, ...)`(`EvalRunnerService.java:256-259`)。

本地开发没问题,但打包成 jar 或容器后:

- 工作目录不一定是 repo root;
- `docs/` 可能不存在或只读;
- 多实例会写各自本地文件,报告不一致。

**建议:** 配置化 `knowsource.eval.report-path`,默认可继续 `docs/eval/report.md`;部署时指向 volume 或 DB 存储。

### I4. 前端没有测试脚本,测试策略缺少前端覆盖

`frontend/package.json:6-9` 只有 `dev/build/preview`,没有 `test`、`lint`、组件测试或 e2e。当前后端测试覆盖已经很扎实,但前端关键路径(SSE 解析、auth refresh、upload/publish 流程、Evaluation Markdown 解析)完全靠人工验证。

**建议:** 至少补 Vitest 单测覆盖 `chatStream.ts` 的 SSE frame/error/done 解析,再用 Playwright 覆盖 login → upload → publish → chat 的 demo 主链路。

## 🟡 一致性问题

### C1. EvalController 权限是 service 内手写 ADMIN,文档应说明不是 `@PreAuthorize`

`EvalController.java:18-25` 路由本身没有注解;权限在 `EvalRunnerService.requireAdmin()`(`EvalRunnerService.java:299-303`)里实现。这和项目整体“手写 service 权限校验”的风格一致,但文档若写成 Spring Security 方法注解就不准确。

### C2. Evaluation 前端安全处理做得比文档更细,但报告解析依赖表格列位置

`EvaluationTab.vue:12-16` 使用 `markdown-it({ html:false })`,再 `v-html` 渲染(`EvaluationTab.vue:331-332`),这比直接渲染 Markdown 安全。  
但 `parseReport()` 依赖 markdown 表格列序号(`EvaluationTab.vue:102-108`),如果报告列调整,失败用例摘要会错位。

### C3. Admin 用户页面文件名仍叫 Placeholder

路由 `/admin/users` 指向 `AdminUsersPlaceholder.vue`(`router.ts:14`)。功能已不只是 placeholder(用户列表、创建、改角色都在),建议重命名为 `AdminUsersView.vue`,避免文档和代码阅读时误判“还没做”。

## 🟢 亮点

### G1. 后端测试覆盖已经超过文档原始预期

当前 `src/test` 覆盖了:

- auth/JWT/refresh rotation;
- KB RBAC;
- document ingest/upload/quality report;
- publish/outbox/index poller;
- vector/lexical/hybrid retrieval;
- rerank provider/fallback;
- chat/SSE/QaTrace/session;
- eval API;
- AI resilience/http timeout;
- OSS smoke test(默认跳过,需真实环境变量)。

尤其 `ChatControllerTest.java:853-895` 把 Hybrid 接入链路测实了,这是本次审查能确认 S1 的关键。

### G2. 前端 SSE 采用 fetch + Authorization,方向正确

`chatStream.ts:47-58` 用 `fetch` POST stream,带 Bearer token,避开了原生 EventSource 不能加 header 的问题;401 时先 refresh 再重试(`chatStream.ts:12-16`)。这是文档 §9.3 推荐路线。

### G3. Auth refresh 前端做了并发去重

`auth.ts:8` 的 `refreshRequest` 与 `refresh()` 中的共享 Promise(`auth.ts:61-70`)能避免多个 Axios 401 同时打 refresh,这正好缓解第 2 次审查提到的 refresh rotation 并发竞态。

### G4. Evaluation tab 对报告展示比较完整

能运行 golden set、显示 KPI、失败用例、原始 Markdown 报告,且 HTML disabled。作为面试演示面板已经够用,主要问题是指标口径和运行数据清理。

------

## 第 5 次审查总结

| 维度          | 结论                                                         |
| :------------ | :----------------------------------------------------------- |
| **评测**      | 能走生产 service path,但 `Recall@5` 口径偏松,且运行会污染数据。 |
| **前端**      | 主流程完整,SSE+auth 方向正确;但 error 事件丢失是实 bug,调参控件缺失。 |
| **测试**      | 后端测试覆盖扎实;前端无自动化测试脚本,文档测试策略需补。 |
| **部署**      | Compose 仍只起 postgres,一键部署文档不成立。 |
| **文档对齐度** | 前端/评测/部署章节需要按真实实现重写,尤其 Hybrid、Eval 指标、Compose。 |

**本批次最紧急的三件事:**

1. 🔴 **修前端 SSE `event:error` 处理**(S1);
2. 🔴 **修正 Eval 指标口径:Recall@5 ≠ citation hit rate**(S2);
3. 🔴 **补 app/frontend compose 或改文档部署口径**(S3)。

# 五次审查总账:文档完整性补丁清单

## 必须改的架构文档章节

| 章节 | 必改内容 |
| :--- | :------- |
| §2.3/§2.4 | Hybrid 检索、OCR/质量报告已经进入当前实现,不能继续写 Out of Scope;若定位 v1.1,需明确“当前代码已实现,默认/开关状态”。 |
| §4.2/§7.4 | 生产 RAG 路径不是 Spring AI Advisor 链,而是 `ChatService + RetrievalService + JdbcClient SQL`;重写组件图和调用链。 |
| §5.1/§5.2 | 依赖是 OpenAI-compatible starter + DashScope compatible endpoint,不是 `spring-ai-alibaba-starter-dashscope`。 |
| §6.3/§13.3 | 补 V3-V9 migrations: chat session/message、qa_trace_id、ingest quality、FTS hybrid、global EDITOR 变更。 |
| §6.4 | 删除 `KnowSourceVectorStore extends PgVectorStore` 叙事,改为 `VectorIndexService`/`VectorSearchService` 手写 SQL。 |
| §7.2 | 切块策略改成当前字符级语义切分;若仍要求 token overlap,代码需补实现。 |
| §7.3 | publish 幂等、解析自动重试、解析僵死回收、下架 Outbox 与代码不符,需二选一修文档或修代码。 |
| §7.4-7.7 | 补 Hybrid/RRF/FTS、词法证据门、sources 扩展字段、生成阶段 rewritten query 缺口。 |
| §8/§9 | 全局 EDITOR 权限、真实 API 路由(`/api/kbs/{kbId}/...`)、refresh rotation、TTL、demo user 配置重写。 |
| §10 | Eval 指标口径改为真实实现,或升级代码实现真正 Recall@5 / keyword / faithfulness。 |
| §11 | 补线程池拒绝策略真实行为、token_usage 空字段、Outbox/向量/线程池指标、前端/后端测试策略。 |
| §12 | 前端功能表按真实 UI 更新:当前缺 profile/topK/retrievalMode 控件,但多了会话历史、Evaluation、Admin 用户管理等。 |
| §13 | Compose 当前只有 PostgreSQL;部署章节必须改为“两进程本地启动”或补完整 app/frontend compose。 |

## 代码侧优先修复队列

| 优先级 | 修复项 |
| :----- | :----- |
| P0 | 轮换泄露/疑似泄露 API key,清理历史;修前端 SSE error;publish 幂等;明确 Hybrid 文档边界。 |
| P1 | 生成阶段使用 rewritten query/历史;解析僵死回收;线程池 CallerRunsPolicy/拒绝保护;Eval 指标改名或真实化。 |
| P2 | 前端调参控件;Eval KB 清理;report path 配置化;QaTrace token usage;前端 Vitest/Playwright。 |
| P3 | DocumentService SQL 去重;存储清理策略;AdminUsers 文件重命名;VectorIndex JSON/Jackson 优化。 |

## 最终判断

代码实现已经明显超过最初 MVP 文档:后端 RAG、Hybrid、QaTrace、Eval、前端控制台都比文档描述更完整。但文档仍保留大量早期设计叙事(Spring AI Advisor、KnowSourceVectorStore、Out of Scope Hybrid/OCR、一键 compose),导致“代码很好,文档讲错”的风险很高。

下一步最划算的工作不是继续加功能,而是做一次**架构文档现实化修订**:把当前真实实现写清楚,再把未兑现的设计(token overlap、解析重试、ARCHIVE Outbox、真正 Recall@5、一键部署)标成后续增强。这样面试叙事会稳很多。

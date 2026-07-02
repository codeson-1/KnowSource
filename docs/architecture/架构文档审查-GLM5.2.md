我已完整通读这份 2499 行的架构文档。整体质量很高——延迟向量化 + Outbox、检索层 `(doc_id, doc_version)` 精确匹配、PostProcessor 早推 sources、W1 Spike 门禁等设计都很见功力。下面按**严重程度**列出我发现的不合理处与可优化点。

------

## 一、文档内部一致性矛盾(应优先修正,面试时易被追问)

### 1. Flyway 迁移文件命名/顺序全文不一致

同一份文档里出现了至少 4 种说法:

| 位置                | 说法                                                        |
| :------------------ | :---------------------------------------------------------- |
| 修订历史 rev3 (L21) | `V1__vector_store.sql`、`V2__business_tables.sql`           |
| §5.3 注释 (L542)    | `V2__vector_store.sql`                                      |
| §6.4 (L834)         | 「Flyway **扩展冗余列**(`V2__vector_columns.sql`)」         |
| §13.3 (L2097)       | `V1__vector_store.sql`(完整建表)+ `V2__business_tables.sql` |
| 附录 D 标题 (L2324) | `V1__init_business.sql`                                     |

读者会完全搞不清到底有几个迁移、什么顺序。**建议统一为 §13.3 的口径,并删除其他位置的历史命名。**

### 2. `vector_store` 表到底是"扩展默认表"还是"完整自建"?

- §6.4 说"Spring AI PgVectorStore 默认表经 Flyway **扩展冗余列**"——暗示 `ALTER TABLE`
- §13.3 + ADR-001 又说关闭 `initialize-schema`,由 Flyway **完整建表**

这两者互斥。实际实现是后者(完整自建),§6.4 的措辞应改为"由 Flyway 完整建表(含冗余列)",否则与 `initialize-schema=false` 的时序说明(L2102)自相矛盾。

### 3. FR 编号乱序

§3.2 功能需求:`FR-01~05` → 跳到 `FR-11, FR-12` → 又回到 `FR-06~10`。建议按顺序重排。

### 4. `refresh_tokens.revoked_at` 语义写反

§6.3.2 说明"登出/改密/禁用时**置空或删除**"。但 `revoked_at` 语义是:**NULL = 未撤销,有值 = 已撤销**。登出应 `SET revoked_at = now()`(置非空),而非"置空"。§8.6.2 的描述是对的,两处冲突。

### 5. `chatExecutor` 拒绝策略注释错误

§11.6.1 注释"CallerRunsPolicy(排队等待)"。`CallerRunsPolicy` 的语义是**队列满时由调用线程直接执行任务**(会阻塞调用者),不是"排队等待"。这会影响对问答接口阻塞行为的理解。

------

## 二、安全设计需加强

### 6. Access Token TTL = 24h 过长 ⚠️

§8.6.2 access token 默认 **86400s (24h)**。业界惯例是 access token **15min~1h**,refresh token 才是长周期。24h 的 access token 一旦泄露(XSS 拿到 localStorage),攻击窗口极大;`token_version` 只能覆盖"改密/禁用",覆盖不了"token 被盗但用户无感知"。

**建议:** access token 15~30min,refresh token 7 天,前端 401 时走 refresh。

### 7. Refresh Token 缺少轮换(rotation)

§8.6.2 只说 refresh 时"签发新 accessToken",没提是否**同时轮换 refreshToken 并作废旧的**。无 rotation 的 refresh token 一旦被盗,可无限续期。建议:每次 refresh 签发新 refreshToken + 作废旧 `token_hash`。

### 8. SSE 长连接鉴权窗口

§9.3 用 `fetch + Authorization` 做 SSE(正确,因为 EventSource 不支持自定义 header)。但流式问答可能持续数十秒,**连接中途 access token 可能过期**。文档没说明长流期间如何处理鉴权失效。建议:鉴权只在建连时校验(可接受),并在文档中明确这一点。

### 9. 发布接口缺幂等防重

`POST /documents/{docId}/publish` 连点两次会写两条 Outbox 事件。虽然声称 `(docId+docVersion)` 幂等,但:

- Outbox 表无唯一约束(附录 D 没建)
- 两个 consumer 可并发消费两条事件,都执行 `delete(docId)+add`,产生竞态

**建议:** 发布前检查 `status`,已是 PUBLISHED 则直接返回当前 index_status;或对 `(doc_id, doc_version, event_type)` 加唯一约束。

------

## 三、设计正确性问题

### 10. `OverlapTokenSplitter` 实现语义有问题 ⚠️

§7.2.2 的实现是"先 `super.apply` 切无重叠块 → 再 `mergeWithOverlap` 给相邻块补 overlap"。这会导致每个 chunk 实际长度 ≈ `chunkSize + overlapTokens`,**违反了"child chunkSize = 400 token"的约束**。

真正的滑动窗口应是:步长 = `chunkSize - overlap`,每块固定 `chunkSize`。当前实现要么重新定义 chunkSize 语义,要么换算法。这点 W5 的 golden set 对比时会有体现,但设计层面应说清。

### 11. 索引消费的 `delete → add` 非原子

§7.3.2 / §6.2.1:`vectorStore.delete(docId)` 后 `vectorStore.add(docs)`,两步事务外。若 delete 成功、add 抛异常,该文档向量已清空。虽然 catch 走 `markFailed` 把 `index_status=FAILED`(使其不在 `syncedDocVersions` 中、检索不命中,是安全的),重试会重新 add——**但文档没有明确这条恢复路径**。建议补一句:"delete 成功 + add 失败 → index_status=FAILED,重试时重新 add,中间窗口检索安全(因 FAILED 不在白名单)"。

### 12. 检索 Filter 的性能/必要性权衡未充分说明

§7.4.2 要求每次问答构造 `(doc_id, doc_version) IN (长列表)` 的 OR 展开 SQL。当知识库有几百篇文档时:

- Filter 表达式构造开销大
- 大量 OR 谓词可能影响 B-tree 效率
- 每次都要查 `documents` 表(虽有 30s 缓存)

而实际上,既然"向量表只写入 SYNCED 文档" + "下架时 delete"是不变量,**向量表里的每一行本就都该可检索**,`(doc_id, doc_version)` 过滤在 MVP 是纯防御性冗余。文档承认了这点(L1078),但没量化代价。**建议:** 要么在文档中明确这是为"未来无中断版本切换"预留、MVP 可考虑简化为仅 `kb_id + status`;要么评估大库下 OR 列表的 SQL plan。

### 13. 缺少级联删除策略

§9.2 有 `DELETE /kb/{kbId}`,但全文未说明级联:删知识库时,其下文档的向量、chunk_parents/children、Outbox 事件、chat_sessions 如何处理?这是数据一致性隐患,且下架/删除时向量必须同步 `vectorStore.delete`,否则变成孤儿向量(永远检索得到但业务表已删)。**建议补一节"删除语义"。**

------

## 四、重要缺失项

### 14. 完全没有测试策略章节

2499 行文档只有 EvalRunner(效果评测)和 W1 Spike,缺少:

- 单元/集成测试分层与覆盖率目标
- Outbox 状态机、并发领取(`FOR UPDATE SKIP LOCKED`)如何测
- DashScope 如何 mock(避免测试消耗配额/受网络影响)
- 检索 Filter 正确性的回归测试

面试项目里测试策略是加分项,建议补 §11.7。

### 15. 缺少运维关键指标

§11.2 的 Micrometer 指标偏 RAG 链路,缺运维核心:

- **Outbox 积压量**(PENDING/FAILED 事件数)——判断索引是否堵了
- **向量表行数 / index_status 分布**
- **线程池拒绝数 / 队列深度**(CallerRunsPolicy 触发频率)

### 16. 缺少健康检查 / 探针

§13.1 docker-compose 的 app 没有 healthcheck,也没 readiness/liveness 设计。DashScope 连不上、DB 连不上时应用行为如何?MVP 单实例也该有 actuator health。

### 17. 轮询间隔未定义

`IndexEventConsumer` 用 `@Scheduled` 轮询 Outbox,但**轮询间隔未写**。KSI 要求"发布索引同步 P95 < 60s",轮询间隔直接影响该指标(若 30s 轮询一次,光等待就占掉一半预算)。建议明确(如 fixedDelay=5s)。

------

## 五、可优化点(非必须)

### 18. 创建知识库权限过紧

§8.2.1 矩阵 + §9.2:只有全局 ADMIN 能 `POST /kb`。这意味着普通 VIEWER 用户永远无法拥有自己的知识库,必须找 ADMIN 代建——对"企业知识库平台"来说偏紧,演示也不便。**建议:** 允许任意登录用户创建知识库(自动成为该库 OWNER),ADMIN 只管全局。这也更符合 US 故事的自然流程。

### 19. embedding 维度硬编码耦合

`V1__vector_store.sql` 写死 `vector(1024)`,与 text-embedding-v3 绑定。§5.1 声称"接口可替换",但换模型=改 DDL。建议在 ADR-001 明确这一耦合点,或在配置中暴露维度。

### 20. 附录 D 有冗余无用索引

末尾 `CREATE INDEX idx_documents_kb_status ON documents(kb_id, status)`(L2473)被前面的 `idx_documents_kb_status_index (kb_id, status, index_status)` 完全覆盖,冗余可删。

### 21. HNSW 参数未配置

§13.3 HNSW 索引用默认 `m`/`ef_construction`,检索 `ef_search` 也未提。MVP 可接受,但建议在"参数调优"里留个钩子。

### 22. QaTrace 数据保留策略缺失

QaTrace 全量存原始 query + answer(可能含敏感问答内容),§11.5 只说"生产可采样"日志,但表本身无保留期/清理/脱敏策略。建议补 TTL 清理。

------

## 总结优先级

| 级别              | 项                                                           | 编号     |
| :---------------- | :----------------------------------------------------------- | :------- |
| 🔴 必改(矛盾/错误) | 迁移命名统一、vector_store 初始化口径、FR 乱序、revoked_at 语义、CallerRunsPolicy 注释 | 1-5      |
| 🟠 安全            | Access Token TTL、refresh rotation、发布幂等                 | 6,7,9    |
| 🟡 设计正确性      | OverlapSplitter 语义、delete-add 恢复路径、级联删除          | 10,11,13 |
| 🟢 补全            | 测试策略、运维指标、健康检查、轮询间隔                       | 14-17    |

需要我针对其中某几项**直接修订文档**(比如先修一致性矛盾 + 安全项),还是先就某个具体设计点深入讨论?

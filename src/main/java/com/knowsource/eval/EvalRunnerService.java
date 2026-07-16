package com.knowsource.eval;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.knowsource.chat.ChatRequest;
import com.knowsource.chat.ChatResponse;
import com.knowsource.chat.ChatService;
import com.knowsource.chat.SourceCitation;
import com.knowsource.document.CreateDocumentRequest;
import com.knowsource.document.DocumentIngestResponse;
import com.knowsource.document.DocumentService;
import com.knowsource.document.ResourceNotFoundException;
import com.knowsource.index.DocumentIndexOutboxService;
import com.knowsource.kb.CreateKnowledgeBaseRequest;
import com.knowsource.kb.KnowledgeBaseService;
import com.knowsource.security.CurrentUser;
import com.knowsource.security.CurrentUserService;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

@Service
public class EvalRunnerService {

    private static final Path GOLDEN_SET = Path.of("docs/eval/golden-set.jsonl");
    private static final Path REPORT = Path.of("docs/eval/report.md");
    private static final String REPORT_PATH = "docs/eval/report.md";
    private static final String EXPECTED_REFUSAL = "拒答";

    private final ObjectMapper objectMapper;
    private final CurrentUserService currentUserService;
    private final KnowledgeBaseService knowledgeBaseService;
    private final DocumentService documentService;
    private final DocumentIndexOutboxService documentIndexOutboxService;
    private final ChatService chatService;
    private final JdbcClient jdbcClient;
    private final FaithfulnessEvaluator faithfulnessEvaluator;

    public EvalRunnerService(
            ObjectMapper objectMapper,
            CurrentUserService currentUserService,
            KnowledgeBaseService knowledgeBaseService,
            DocumentService documentService,
            DocumentIndexOutboxService documentIndexOutboxService,
            ChatService chatService,
            JdbcClient jdbcClient,
            FaithfulnessEvaluator faithfulnessEvaluator) {
        this.objectMapper = objectMapper;
        this.currentUserService = currentUserService;
        this.knowledgeBaseService = knowledgeBaseService;
        this.documentService = documentService;
        this.documentIndexOutboxService = documentIndexOutboxService;
        this.chatService = chatService;
        this.jdbcClient = jdbcClient;
        this.faithfulnessEvaluator = faithfulnessEvaluator;
    }

    public EvalRunResponse runGoldenSet() {
        requireAdmin();
        List<GoldenCase> goldenCases = loadGoldenSet();
        LocalDateTime generatedAt = LocalDateTime.now();
        // P2-2: reuse existing eval KB if available (same 4 docs, all SYNCED) to avoid accumulation
        String kbId = findOrCreateEvalKb(generatedAt);

        // Phase 1: ask all golden cases and collect raw pairs
        record EvalRaw(ChatResponse response, GoldenCase goldenCase) {}
        List<EvalRaw> raws = new ArrayList<>();
        for (GoldenCase goldenCase : goldenCases) {
            ChatResponse response = askGoldenCase(kbId, goldenCase);
            raws.add(new EvalRaw(response, goldenCase));
            waitForTrace(response.qaTraceId());
        }

        // Phase 2: build case responses with faithfulness evaluation (LLM-as-Judge, post-hoc)
        List<EvalCaseResponse> results = new ArrayList<>();
        for (EvalRaw raw : raws) {
            GoldenCase goldenCase = raw.goldenCase();
            ChatResponse response = raw.response();
            Double faithfulness = null;
            if (!goldenCase.outOfScope() && !response.refused() && !response.sources().isEmpty()) {
                faithfulness = faithfulnessEvaluator.evaluate(
                        goldenCase.question(), response.answer(), response.sources());
            }
            results.add(toCaseResponse(goldenCase, response, faithfulness));
        }

        EvalSummaryResponse summary = summarize(results);
        String report = renderReport(generatedAt, summary, results);
        writeReport(report);
        return new EvalRunResponse(kbId, generatedAt, summary, results, REPORT_PATH);
    }

    public EvalReportResponse latestReport() {
        requireAdmin();
        if (!Files.exists(REPORT)) {
            throw new ResourceNotFoundException("Eval report not found.");
        }
        try {
            LocalDateTime updatedAt = LocalDateTime.ofInstant(Files.getLastModifiedTime(REPORT).toInstant(), ZoneId.systemDefault());
            return new EvalReportResponse(updatedAt, REPORT_PATH, Files.readString(REPORT, StandardCharsets.UTF_8));
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to read eval report.", ex);
        }
    }

    private void seedAndPublishDocuments(String kbId) {
        publishDocument(createDocument(kbId, "年假制度",
                """
                        # 年假制度

                        制度编号：LEAVE-2024-01

                        ## 假期额度

                        全职员工每年享有 10 天年假。工龄满 10 年享 15 天年假。未使用的年假最多可以结转 5 天到下一自然年，跨年结转需 VP 审批。

                        ## 审批流程

                        员工休年假前必须先获得直属经理审批。连续请假超过 5 天的年假申请，还需要 HR 复核。
                        """));
        publishDocument(createDocument(kbId, "办公安全制度",
                """
                        # 办公安全制度

                        制度编号：SEC-2024-02

                        ## 办公区出入

                        员工进入办公区必须佩戴安全工牌。访客需要在前台登记，并佩戴访客工牌。工牌补办费用 50 元。

                        ## 事件上报

                        如果安全工牌丢失，员工必须在 24 小时内向安全部门上报，以便及时停用门禁卡。P0 事件需 30 分钟内响应。
                        """));
        publishDocument(createDocument(kbId, "报销制度",
                """
                        # 报销制度

                        制度编号：EXP-2024-03

                        ## 提交时限

                        报销票据应在费用发生后 30 天内通过财务门户提交。单笔超过 5000 元的报销需附加情况说明。

                        ## 报销额度

                        | 类别 | 额度 |
                        | --- | --- |
                        | 餐费 | 120 |
                        | 住宿 | 800（一线城市上浮 20%） |
                        | 市内交通 | 300 |
                        """));
        publishDocument(createDocument(kbId, "远程办公制度",
                """
                        # 远程办公制度

                        制度编号：REMOTE-2024-01

                        员工获得团队负责人审批后，每周可以远程办公 2 天。强制线下培训日不得安排远程办公。VPN 断线需 15 分钟内上报。
                        """));
        // 以下为干扰文档——与目标文档语义相近但不含制度编号，用于逼出 hybrid 的关键词匹配优势
        publishDocument(createDocument(kbId, "考勤管理制度",
                """
                        # 考勤管理制度

                        ## 工作时间

                        标准工作时间为周一至周五 9:00-18:00，午休 1 小时。弹性工作制员工可在 7:00-10:00 之间到岗。

                        ## 请假流程

                        员工请假需提前在 OA 系统提交申请。病假需附医院证明，事假每年累计不超过 15 天。
                        """));
        publishDocument(createDocument(kbId, "差旅费用管理制度",
                """
                        # 差旅费用管理制度

                        ## 交通标准

                        高铁二等座或飞机经济舱，单程超过 800 公里的可选高铁一等座。市内交通实报实销，每日上限 200 元。

                        ## 住宿标准

                        一线城市住宿标准 600 元/晚，非一线城市 400 元/晚。超标部分由个人承担。
                        """));
        publishDocument(createDocument(kbId, "设备与网络安全管理",
                """
                        # 设备与网络安全管理

                        ## 设备使用

                        公司配发电脑不得安装未经授权的软件。离职时需归还全部设备并清除个人数据。

                        ## 网络安全

                        禁止在公共 Wi-Fi 环境下访问公司内网。敏感数据传输必须使用加密通道。
                        """));
        // 第二批干扰文档——进一步压缩语义空间，逼出 hybrid 关键词匹配优势
        publishDocument(createDocument(kbId, "员工培训管理制度",
                """
                        # 员工培训管理制度

                        ## 入职培训

                        新员工入职后需在一周内完成公司文化与规章制度培训。各部门需指定导师进行岗位技能带教。

                        ## 年度培训

                        每位员工每年需完成不少于 40 学时的专业技能培训。培训完成情况纳入年度绩效考核。
                        """));
        publishDocument(createDocument(kbId, "采购审批制度",
                """
                        # 采购审批制度

                        ## 审批权限

                        单笔采购金额 2000 元以下由部门经理审批，2000-10000 元需总监审批，超过 10000 元需总经理审批。

                        ## 供应商管理

                        所有供应商需通过资质审核后方可合作。年度采购额超过 50000 元的供应商需签订框架协议。
                        """));
        publishDocument(createDocument(kbId, "消防安全管理制度",
                """
                        # 消防安全管理制度

                        ## 日常巡查

                        每月进行一次消防设施检查，包括灭火器压力、消防栓水压、应急照明等。检查记录需存档备查。

                        ## 应急演练

                        每半年组织一次全员消防疏散演练。各部门安全员需在 5 分钟内完成本区域人员清点。
                        """));
        publishDocument(createDocument(kbId, "会议与协作制度",
                """
                        # 会议与协作制度

                        ## 会议室预约

                        会议室需提前在 OA 系统预约，单次会议时长不超过 2 小时。投影仪等设备使用后需关闭并归位。

                        ## 线上协作

                        跨部门协作项目需在协作平台上创建项目空间。会议纪要在会后 24 小时内同步至项目空间。
                        """));
        publishDocument(createDocument(kbId, "数据备份与恢复制度",
                """
                        # 数据备份与恢复制度

                        ## 备份策略

                        核心业务数据每日凌晨全量备份，保留最近 30 天。备份文件异地存储，物理距离不少于 500 公里。

                        ## 恢复演练

                        每季度进行一次数据恢复演练。关键系统恢复时间不超过 4 小时。
                        """));
    }

    private String createDocument(String kbId, String title, String content) {
        DocumentIngestResponse response = documentService.ingest(kbId, new CreateDocumentRequest(title, content));
        waitForIngestReady(response.document().id());
        return response.document().id();
    }

    private void publishDocument(String docId) {
        documentService.publish(docId);
        if (!documentIndexOutboxService.processNextPendingEvent()) {
            throw new IllegalStateException("No pending index event was processed for document " + docId + ".");
        }
    }

    private ChatResponse askGoldenCase(String kbId, GoldenCase goldenCase) {
        if (goldenCase.setupQuestion() == null || goldenCase.setupQuestion().isBlank()) {
            return ask(kbId, goldenCase.question(), null, goldenCase.profile());
        }
        ChatResponse setupResponse = ask(kbId, goldenCase.setupQuestion(), null, "auto");
        waitForTrace(setupResponse.qaTraceId());
        return ask(kbId, goldenCase.question(), setupResponse.sessionId(), goldenCase.profile());
    }

    private ChatResponse ask(String kbId, String question, String sessionId, String profile) {
        return chatService.answer(kbId, new ChatRequest(question, 5, profile, sessionId));
    }

    private List<GoldenCase> loadGoldenSet() {
        try {
            List<GoldenCase> cases = new ArrayList<>();
            for (String line : Files.readAllLines(GOLDEN_SET, StandardCharsets.UTF_8)) {
                if (!line.isBlank()) {
                    cases.add(objectMapper.readValue(line, GoldenCase.class));
                }
            }
            if (cases.isEmpty()) {
                throw new IllegalArgumentException("Golden set is empty.");
            }
            return cases;
        } catch (IOException ex) {
            throw new ResourceNotFoundException("Golden set not found.");
        }
    }

    private EvalCaseResponse toCaseResponse(GoldenCase goldenCase, ChatResponse response, Double faithfulness) {
        List<String> sourceTitles = response.sources().stream()
                .map(SourceCitation::title)
                .toList();

        // 文档命中率: 期望文档标题是否出现在 Top-5 来源中（retrieval 质量）
        boolean documentHit = !goldenCase.outOfScope()
                && goldenCase.expectedDocTitle() != null
                && sourceTitles.contains(goldenCase.expectedDocTitle());

        boolean refusalCorrect = goldenCase.outOfScope() == response.refused();

        String answerText = extractAnswer(response);
        String matchedKeyword = goldenCase.expectedKeywords().stream()
                .filter(kw -> answerText != null && answerText.contains(kw))
                .findFirst().orElse(null);
        boolean keywordHit = matchedKeyword != null;

        // 引用准确率: 文档命中 且 答案包含期望关键词（end-to-end 质量，答案是否真正使用了检索到的信息）
        boolean citationHit = documentHit && keywordHit;

        // 通过判定: 范围内用例需文档命中且未拒答；范围外用例需正确拒答
        boolean passed = goldenCase.outOfScope() ? refusalCorrect : documentHit && !response.refused();

        int docRank = 0;
        if (goldenCase.expectedDocTitle() != null) {
            for (int i = 0; i < sourceTitles.size(); i++) {
                if (sourceTitles.get(i).equals(goldenCase.expectedDocTitle())) {
                    docRank = i + 1;
                    break;
                }
            }
        }

        return new EvalCaseResponse(
                goldenCase.id(),
                goldenCase.setupQuestion(),
                goldenCase.question(),
                goldenCase.profile(),
                goldenCase.outOfScope() ? EXPECTED_REFUSAL : goldenCase.expectedDocTitle(),
                response.refused(),
                sourceTitles,
                documentHit,
                citationHit,
                refusalCorrect,
                passed,
                response.qaTraceId(),
                response.sessionId(),
                keywordHit,
                matchedKeyword,
                docRank,
                faithfulness);
    }

    private String extractAnswer(ChatResponse response) {
        return response.answer();
    }

    private EvalSummaryResponse summarize(List<EvalCaseResponse> results) {
        int inScope = (int) results.stream().filter(result -> !EXPECTED_REFUSAL.equals(result.expected())).count();
        int outOfScope = results.size() - inScope;

        // 文档命中率: 期望文档在 Top-5 来源中的命中率（retrieval 质量）
        long documentHits = results.stream().filter(EvalCaseResponse::documentHit).count();
        double documentHitRate = inScope == 0 ? 0.0d : (double) documentHits / inScope;

        // 引用准确率: 文档命中且答案包含期望关键词（end-to-end 质量）
        long citationHits = results.stream().filter(EvalCaseResponse::citationHit).count();
        double citationHitRate = inScope == 0 ? 0.0d : (double) citationHits / inScope;

        long refusalCorrect = results.stream()
                .filter(result -> EXPECTED_REFUSAL.equals(result.expected()))
                .filter(EvalCaseResponse::refusalCorrect)
                .count();
        double refusalAccuracy = outOfScope == 0 ? 0.0d : (double) refusalCorrect / outOfScope;

        List<EvalCaseResponse> inScopeResults = results.stream()
                .filter(r -> !EXPECTED_REFUSAL.equals(r.expected()))
                .toList();

        long keywordHits = inScopeResults.stream().filter(EvalCaseResponse::keywordHit).count();
        double keywordHitRate = inScopeResults.isEmpty() ? 0 : (double) keywordHits / inScopeResults.size();

        double mrr = inScopeResults.stream()
                .mapToDouble(r -> r.docRank() > 0 ? 1.0 / r.docRank() : 0.0)
                .average().orElse(0);

        double meanDocRank = inScopeResults.stream()
                .mapToInt(EvalCaseResponse::docRank)
                .filter(rank -> rank > 0)
                .average().orElse(0);

        // Faithfulness: 对范围内非拒答案例的 LLM-as-Judge 忠实度均值
        Double faithfulness = inScopeResults.stream()
                .filter(r -> !r.refused())
                .map(EvalCaseResponse::faithfulness)
                .filter(f -> f != null)
                .mapToDouble(Double::doubleValue)
                .average()
                .stream()
                .boxed()
                .findFirst()
                .orElse(null);

        return new EvalSummaryResponse(results.size(), inScope, outOfScope,
                documentHitRate, citationHitRate, refusalAccuracy,
                keywordHitRate, mrr, meanDocRank, faithfulness);
    }

    private String renderReport(LocalDateTime generatedAt, EvalSummaryResponse summary, List<EvalCaseResponse> results) {
        StringBuilder report = new StringBuilder();
        report.append("# KnowSource 评测报告\n\n");
        report.append("生成时间: ").append(generatedAt).append("\n\n");
        report.append("| 指标 | 数值 |\n");
        report.append("|---|---:|\n");
        report.append("| 用例总数 | ").append(summary.totalCases()).append(" |\n");
        report.append("| 范围内用例 | ").append(summary.inScopeCases()).append(" |\n");
        report.append("| 范围外用例 | ").append(summary.outOfScopeCases()).append(" |\n");
        report.append("| 文档命中率@5 | ").append(formatPercent(summary.documentHitRate())).append(" |\n");
        report.append("| 引用准确率 | ").append(formatPercent(summary.citationHitRate())).append(" |\n");
        report.append("| 拒答准确率 | ").append(formatPercent(summary.refusalAccuracy())).append(" |\n");
        report.append("| 关键词命中率 | ").append(formatPercent(summary.keywordHitRate())).append(" |\n");
        if (summary.faithfulness() != null) {
            report.append("| 忠实度 (Faithfulness) | ").append(formatPercent(summary.faithfulness())).append(" |\n");
        }
        report.append("| MRR | ").append(String.format("%.3f", summary.mrr())).append(" |\n");
        report.append("| 平均文档排名 | ").append(String.format("%.1f", summary.meanDocRank())).append(" |\n\n");
        report.append("## 用例结果\n\n");
        report.append("| 用例 ID | 前置问题 | 问题 | 期望 | 是否拒答 | 来源文档 | 文档命中 | 引用准确 | 是否通过 | 关键词命中 | 命中关键词 | 文档排名 | 忠实度 | Trace |\n");
        report.append("|---|---|---|---|---|---|---|---|---|---|---|---|---|---|\n");
        for (EvalCaseResponse result : results) {
            report.append("| ")
                    .append(result.id()).append(" | ")
                    .append(escape(result.setupQuestion())).append(" | ")
                    .append(escape(result.question())).append(" | ")
                    .append(result.expected()).append(" | ")
                    .append(result.refused() ? "是" : "否").append(" | ")
                    .append(escape(String.join(", ", result.sourceTitles()))).append(" | ")
                    .append(result.documentHit() ? "是" : "否").append(" | ")
                    .append(result.citationHit() ? "是" : "否").append(" | ")
                    .append(result.passed() ? "是" : "否").append(" | ")
                    .append(result.keywordHit() ? "是" : "否").append(" | ")
                    .append(escape(result.matchedKeyword())).append(" | ")
                    .append(result.docRank() == 0 ? "-" : String.valueOf(result.docRank())).append(" | ")
                    .append(result.faithfulness() != null ? formatPercent(result.faithfulness()) : "-").append(" | ")
                    .append(result.qaTraceId())
                    .append(" |\n");
        }
        return report.toString();
    }

    private void writeReport(String report) {
        try {
            Files.createDirectories(REPORT.getParent());
            Files.writeString(REPORT, report, StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to write eval report.", ex);
        }
    }

    private void waitForIngestReady(String docId) {
        for (int i = 0; i < 120; i++) {
            String status = jdbcClient.sql("""
                    SELECT status
                    FROM ingest_tasks
                    WHERE doc_id = :docId
                    ORDER BY created_at DESC, id DESC
                    LIMIT 1
                    """)
                    .param("docId", docId)
                    .query(String.class)
                    .single();
            if ("READY".equals(status)) {
                return;
            }
            sleep();
        }
        throw new IllegalStateException("Timed out waiting for ingest task READY for " + docId + ".");
    }

    private void waitForTrace(String traceId) {
        for (int i = 0; i < 40; i++) {
            Long count = jdbcClient.sql("SELECT COUNT(*) FROM qa_traces WHERE id = :traceId")
                    .param("traceId", traceId)
                    .query(Long.class)
                    .single();
            if (count == 1) {
                return;
            }
            sleep();
        }
        throw new IllegalStateException("Timed out waiting for QA trace " + traceId + ".");
    }

    private void requireAdmin() {
        CurrentUser currentUser = currentUserService.currentUser();
        if (!"ADMIN".equals(currentUser.globalRole())) {
            throw new AccessDeniedException("ADMIN access is required.");
        }
    }

    private String findOrCreateEvalKb(LocalDateTime generatedAt) {
        // Try to reuse an existing eval KB (prefix "评测知识库", 4 docs, all SYNCED)
        String existingKbId = jdbcClient.sql("""
                SELECT kb.id
                FROM knowledge_bases kb
                WHERE kb.name LIKE '评测知识库%'
                  AND (SELECT COUNT(*) FROM documents WHERE kb_id = kb.id) = 12
                  AND (SELECT COUNT(*) FROM documents WHERE kb_id = kb.id AND status = 'PUBLISHED' AND index_status = 'SYNCED') = 12
                ORDER BY kb.created_at DESC
                LIMIT 1
                """)
                .query(String.class)
                .optional()
                .orElse(null);

        if (existingKbId != null) {
            return existingKbId;
        }

        // Create new eval KB and seed documents
        String kbId = knowledgeBaseService.create(new CreateKnowledgeBaseRequest(
                "评测知识库 " + generatedAt.toString().replace(':', '-'),
                "由基准集评测服务自动生成。")).id();
        seedAndPublishDocuments(kbId);
        return kbId;
    }

    private static void sleep() {
        try {
            Thread.sleep(50);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for eval state.", ex);
        }
    }

    private static String escape(String value) {
        return value == null ? "" : value.replace("|", "\\|").replace("\n", " ");
    }

    private static String formatPercent(double value) {
        return "%.1f%%".formatted(value * 100.0d);
    }
}

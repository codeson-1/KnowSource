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

    public EvalRunnerService(
            ObjectMapper objectMapper,
            CurrentUserService currentUserService,
            KnowledgeBaseService knowledgeBaseService,
            DocumentService documentService,
            DocumentIndexOutboxService documentIndexOutboxService,
            ChatService chatService,
            JdbcClient jdbcClient) {
        this.objectMapper = objectMapper;
        this.currentUserService = currentUserService;
        this.knowledgeBaseService = knowledgeBaseService;
        this.documentService = documentService;
        this.documentIndexOutboxService = documentIndexOutboxService;
        this.chatService = chatService;
        this.jdbcClient = jdbcClient;
    }

    public EvalRunResponse runGoldenSet() {
        requireAdmin();
        List<GoldenCase> goldenCases = loadGoldenSet();
        LocalDateTime generatedAt = LocalDateTime.now();
        String kbId = knowledgeBaseService.create(new CreateKnowledgeBaseRequest(
                "评测知识库 " + generatedAt.toString().replace(':', '-'),
                "由基准集评测服务自动生成。")).id();
        seedAndPublishDocuments(kbId);

        List<EvalCaseResponse> results = new ArrayList<>();
        for (GoldenCase goldenCase : goldenCases) {
            ChatResponse response = askGoldenCase(kbId, goldenCase);
            results.add(toCaseResponse(goldenCase, response));
            waitForTrace(response.qaTraceId());
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

                        ## 假期额度

                        全职员工每年享有 10 天年假。未使用的年假最多可以结转 5 天到下一自然年。

                        ## 审批流程

                        员工休年假前必须先获得直属经理审批。连续请假超过 5 天的年假申请，还需要 HR 复核。
                        """));
        publishDocument(createDocument(kbId, "办公安全制度",
                """
                        # 办公安全制度

                        ## 办公区出入

                        员工进入办公区必须佩戴安全工牌。访客需要在前台登记，并佩戴访客工牌。

                        ## 事件上报

                        如果安全工牌丢失，员工必须在 24 小时内向安全部门上报，以便及时停用门禁卡。
                        """));
        publishDocument(createDocument(kbId, "报销制度",
                """
                        # 报销制度

                        ## 提交时限

                        报销票据应在费用发生后 30 天内通过财务门户提交。

                        ## 报销额度

                        | 类别 | 额度 |
                        | --- | --- |
                        | 餐费 | 120 |
                        | 住宿 | 800 |
                        | 市内交通 | 300 |
                        """));
        publishDocument(createDocument(kbId, "远程办公制度",
                """
                        # 远程办公制度

                        员工获得团队负责人审批后，每周可以远程办公 2 天。强制线下培训日不得安排远程办公。
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

    private EvalCaseResponse toCaseResponse(GoldenCase goldenCase, ChatResponse response) {
        List<String> sourceTitles = response.sources().stream()
                .map(source -> source.title())
                .toList();
        boolean citationHit = !goldenCase.outOfScope()
                && goldenCase.expectedDocTitle() != null
                && sourceTitles.contains(goldenCase.expectedDocTitle());
        boolean refusalCorrect = goldenCase.outOfScope() == response.refused();
        boolean passed = goldenCase.outOfScope() ? refusalCorrect : citationHit && !response.refused();
        return new EvalCaseResponse(
                goldenCase.id(),
                goldenCase.setupQuestion(),
                goldenCase.question(),
                goldenCase.profile(),
                goldenCase.outOfScope() ? EXPECTED_REFUSAL : goldenCase.expectedDocTitle(),
                response.refused(),
                sourceTitles,
                citationHit,
                refusalCorrect,
                passed,
                response.qaTraceId(),
                response.sessionId());
    }

    private EvalSummaryResponse summarize(List<EvalCaseResponse> results) {
        int inScope = (int) results.stream().filter(result -> !EXPECTED_REFUSAL.equals(result.expected())).count();
        int outOfScope = results.size() - inScope;
        long citationHits = results.stream().filter(EvalCaseResponse::citationHit).count();
        long refusalCorrect = results.stream()
                .filter(result -> EXPECTED_REFUSAL.equals(result.expected()))
                .filter(EvalCaseResponse::refusalCorrect)
                .count();
        double citationRate = inScope == 0 ? 0.0d : (double) citationHits / inScope;
        double refusalAccuracy = outOfScope == 0 ? 0.0d : (double) refusalCorrect / outOfScope;
        return new EvalSummaryResponse(results.size(), inScope, outOfScope, citationRate, citationRate, refusalAccuracy);
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
        report.append("| Recall@5 | ").append(formatPercent(summary.recallAt5())).append(" |\n");
        report.append("| 引用命中率 | ").append(formatPercent(summary.citationHitRate())).append(" |\n");
        report.append("| 拒答准确率 | ").append(formatPercent(summary.refusalAccuracy())).append(" |\n\n");
        report.append("## 用例结果\n\n");
        report.append("| 用例 ID | 前置问题 | 问题 | 期望 | 是否拒答 | 来源文档 | 是否通过 | Trace |\n");
        report.append("|---|---|---|---|---:|---|---:|---|\n");
        for (EvalCaseResponse result : results) {
            report.append("| ")
                    .append(result.id()).append(" | ")
                    .append(escape(result.setupQuestion())).append(" | ")
                    .append(escape(result.question())).append(" | ")
                    .append(result.expected()).append(" | ")
                    .append(result.refused() ? "是" : "否").append(" | ")
                    .append(escape(String.join(", ", result.sourceTitles()))).append(" | ")
                    .append(result.passed() ? "是" : "否").append(" | ")
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

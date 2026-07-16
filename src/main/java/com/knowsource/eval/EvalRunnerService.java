package com.knowsource.eval;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.knowsource.chat.ChatRequest;
import com.knowsource.chat.ChatResponse;
import com.knowsource.chat.ChatService;
import com.knowsource.chat.SourceCitation;
import com.knowsource.document.ResourceNotFoundException;
import com.knowsource.security.CurrentUser;
import com.knowsource.security.CurrentUserService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

import jakarta.annotation.PreDestroy;

/**
 * 评测运行编排服务 —— 负责 Golden Set 评测流程的编排与指标汇总。
 * <p>
 * Phase 1（问答）和 Phase 2（Faithfulness 评测）均使用线程池并发执行，
 * 并发度通过 {@code knowsource.eval.concurrency} 配置（默认 4）。
 * <p>
 * 种子文档 → EvalSeedService，报告渲染 → EvalReportRenderer，历史查询 → EvalHistoryService。
 */
@Service
public class EvalRunnerService {

    private static final Logger log = LoggerFactory.getLogger(EvalRunnerService.class);

    private static final Path GOLDEN_SET = Path.of("docs/eval/golden-set.jsonl");
    private static final Path REPORT = Path.of("docs/eval/report.md");
    static final String REPORT_PATH = "docs/eval/report.md";
    private static final String EXPECTED_REFUSAL = "拒答";

    private static final int EVAL_CONCURRENCY = 4;

    private final ObjectMapper objectMapper;
    private final CurrentUserService currentUserService;
    private final ChatService chatService;
    private final JdbcClient jdbcClient;
    private final FaithfulnessEvaluator faithfulnessEvaluator;
    private final EvalSeedService evalSeedService;
    private final EvalReportRenderer evalReportRenderer;
    private final ExecutorService evalExecutor;

    public EvalRunnerService(
            ObjectMapper objectMapper,
            CurrentUserService currentUserService,
            ChatService chatService,
            JdbcClient jdbcClient,
            FaithfulnessEvaluator faithfulnessEvaluator,
            EvalSeedService evalSeedService,
            EvalReportRenderer evalReportRenderer) {
        this.objectMapper = objectMapper;
        this.currentUserService = currentUserService;
        this.chatService = chatService;
        this.jdbcClient = jdbcClient;
        this.faithfulnessEvaluator = faithfulnessEvaluator;
        this.evalSeedService = evalSeedService;
        this.evalReportRenderer = evalReportRenderer;
        this.evalExecutor = Executors.newFixedThreadPool(EVAL_CONCURRENCY, new EvalThreadFactory());
        log.info("评测线程池已创建，并发度: {}", EVAL_CONCURRENCY);
    }

    @PreDestroy
    void shutdown() {
        log.info("评测线程池正在关闭...");
        evalExecutor.shutdown();
    }

    // ── 主流程 ──────────────────────────────────────────────

    public EvalRunResponse runGoldenSet() {
        requireAdmin();
        List<GoldenCase> goldenCases = loadGoldenSet();
        int total = goldenCases.size();
        log.info("开始评测，共 {} 条用例，并发度: {}", total, EVAL_CONCURRENCY);

        LocalDateTime generatedAt = LocalDateTime.now();
        String kbId = evalSeedService.findOrCreateEvalKb(generatedAt);

        // Phase 1: 并发问答，收集原始对
        long phase1Start = System.currentTimeMillis();
        List<EvalRaw> raws = runPhase1Concurrently(kbId, goldenCases, total);
        log.info("Phase 1 完成，耗时 {} ms", System.currentTimeMillis() - phase1Start);

        // Phase 2: 并发 Faithfulness 评测，构建用例响应
        long phase2Start = System.currentTimeMillis();
        List<EvalCaseResponse> results = runPhase2Concurrently(raws, total);
        log.info("Phase 2 完成，耗时 {} ms", System.currentTimeMillis() - phase2Start);

        EvalSummaryResponse summary = summarize(results);
        evalReportRenderer.renderAndWrite(generatedAt, summary, results);
        log.info("评测报告已生成: {}", REPORT_PATH);

        return new EvalRunResponse(kbId, generatedAt, summary, results, REPORT_PATH);
    }

    public EvalReportResponse latestReport() {
        requireAdmin();
        if (!Files.exists(REPORT)) {
            throw new ResourceNotFoundException("Eval report not found.");
        }
        try {
            LocalDateTime updatedAt = LocalDateTime.ofInstant(
                    Files.getLastModifiedTime(REPORT).toInstant(), ZoneId.systemDefault());
            return new EvalReportResponse(updatedAt, REPORT_PATH,
                    Files.readString(REPORT, StandardCharsets.UTF_8));
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to read eval report.", ex);
        }
    }

    // ── Phase 1: 并发问答 ─────────────────────────────────

    private List<EvalRaw> runPhase1Concurrently(String kbId, List<GoldenCase> goldenCases, int total) {
        AtomicInteger completed = new AtomicInteger(0);
        List<Future<EvalRaw>> futures = new ArrayList<>();

        for (GoldenCase goldenCase : goldenCases) {
            futures.add(evalExecutor.submit(() -> {
                ChatResponse response = askGoldenCase(kbId, goldenCase);
                waitForTrace(response.qaTraceId());
                int done = completed.incrementAndGet();
                log.info("[Phase 1] 进度 {}/{} — {}", done, total, goldenCase.id());
                return new EvalRaw(response, goldenCase);
            }));
        }

        List<EvalRaw> raws = new ArrayList<>();
        for (Future<EvalRaw> future : futures) {
            try {
                raws.add(future.get());
            } catch (Exception e) {
                futures.forEach(f -> f.cancel(true));
                throw new IllegalStateException("Phase 1 并发执行失败: " + e.getMessage(), e);
            }
        }
        return raws;
    }

    // ── Phase 2: 并发 Faithfulness 评测 ───────────────────

    private List<EvalCaseResponse> runPhase2Concurrently(List<EvalRaw> raws, int total) {
        AtomicInteger completed = new AtomicInteger(0);
        List<Future<EvalCaseResponse>> futures = new ArrayList<>();

        for (EvalRaw raw : raws) {
            futures.add(evalExecutor.submit(() -> {
                GoldenCase goldenCase = raw.goldenCase();
                ChatResponse response = raw.response();
                Double faithfulness = null;
                if (!goldenCase.outOfScope() && !response.refused() && !response.sources().isEmpty()) {
                    faithfulness = faithfulnessEvaluator.evaluate(
                            goldenCase.question(), response.answer(), response.sources());
                }
                EvalCaseResponse result = toCaseResponse(goldenCase, response, faithfulness);
                int done = completed.incrementAndGet();
                log.info("[Phase 2] 进度 {}/{} — {}", done, total, goldenCase.id());
                return result;
            }));
        }

        List<EvalCaseResponse> results = new ArrayList<>();
        for (Future<EvalCaseResponse> future : futures) {
            try {
                results.add(future.get());
            } catch (Exception e) {
                futures.forEach(f -> f.cancel(true));
                throw new IllegalStateException("Phase 2 并发执行失败: " + e.getMessage(), e);
            }
        }
        return results;
    }

    // ── Golden Set 加载 ────────────────────────────────────

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

    // ── 问答执行 ──────────────────────────────────────────

    private ChatResponse askGoldenCase(String kbId, GoldenCase goldenCase) {
        if (goldenCase.setupQuestion() == null || goldenCase.setupQuestion().isBlank()) {
            return ask(kbId, goldenCase.question(), null, goldenCase.profile());
        }
        // 多轮对话前置问题 → 追问，必须在同一线程内顺序执行以保持会话
        ChatResponse setupResponse = ask(kbId, goldenCase.setupQuestion(), null, "auto");
        waitForTrace(setupResponse.qaTraceId());
        return ask(kbId, goldenCase.question(), setupResponse.sessionId(), goldenCase.profile());
    }

    private ChatResponse ask(String kbId, String question, String sessionId, String profile) {
        return chatService.answer(kbId, new ChatRequest(question, 5, profile, sessionId));
    }

    // ── 用例评估 ──────────────────────────────────────────

    private EvalCaseResponse toCaseResponse(GoldenCase goldenCase, ChatResponse response, Double faithfulness) {
        List<String> sourceTitles = response.sources().stream()
                .map(SourceCitation::title)
                .toList();

        boolean documentHit = !goldenCase.outOfScope()
                && goldenCase.expectedDocTitle() != null
                && sourceTitles.contains(goldenCase.expectedDocTitle());

        boolean refusalCorrect = goldenCase.outOfScope() == response.refused();

        String answerText = response.answer();
        String matchedKeyword = goldenCase.expectedKeywords().stream()
                .filter(kw -> answerText != null && answerText.contains(kw))
                .findFirst().orElse(null);
        boolean keywordHit = matchedKeyword != null;

        boolean citationHit = documentHit && keywordHit;
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

    // ── 指标汇总 ──────────────────────────────────────────

    private EvalSummaryResponse summarize(List<EvalCaseResponse> results) {
        int inScope = (int) results.stream()
                .filter(result -> !EXPECTED_REFUSAL.equals(result.expected())).count();
        int outOfScope = results.size() - inScope;

        long documentHits = results.stream().filter(EvalCaseResponse::documentHit).count();
        double documentHitRate = inScope == 0 ? 0.0d : (double) documentHits / inScope;

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
        double keywordHitRate = inScopeResults.isEmpty() ? 0
                : (double) keywordHits / inScopeResults.size();

        double mrr = inScopeResults.stream()
                .mapToDouble(r -> r.docRank() > 0 ? 1.0 / r.docRank() : 0.0)
                .average().orElse(0);

        double meanDocRank = inScopeResults.stream()
                .mapToInt(EvalCaseResponse::docRank)
                .filter(rank -> rank > 0)
                .average().orElse(0);

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

    // ── 等待工具 ──────────────────────────────────────────

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

    private static void sleep() {
        try {
            Thread.sleep(50);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for eval state.", ex);
        }
    }

    // ── 权限 ──────────────────────────────────────────────

    private void requireAdmin() {
        CurrentUser currentUser = currentUserService.currentUser();
        if (!"ADMIN".equals(currentUser.globalRole())) {
            throw new AccessDeniedException("ADMIN access is required.");
        }
    }

    // ── 内部类型 ──────────────────────────────────────────

    private record EvalRaw(ChatResponse response, GoldenCase goldenCase) {
    }

    /**
     * 评测线程工厂，使用 daemon 线程避免阻塞 JVM 退出。
     */
    private static class EvalThreadFactory implements ThreadFactory {
        private final AtomicInteger counter = new AtomicInteger(1);

        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "eval-worker-" + counter.getAndIncrement());
            t.setDaemon(true);
            return t;
        }
    }
}

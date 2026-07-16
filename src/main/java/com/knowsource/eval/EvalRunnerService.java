package com.knowsource.eval;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.knowsource.chat.ChatRequest;
import com.knowsource.chat.ChatResponse;
import com.knowsource.chat.ChatService;
import com.knowsource.chat.SourceCitation;
import com.knowsource.document.ResourceNotFoundException;
import com.knowsource.security.CurrentUserService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import jakarta.annotation.PreDestroy;

/**
 * 评测运行编排服务 —— 负责 Golden Set 评测流程的编排与指标汇总。
 * <p>
 * Phase 1（问答）和 Phase 2（Faithfulness 评测）均使用线程池并发执行。
 * 种子文档 → EvalSeedService，报告渲染 → EvalReportRenderer，历史查询 → EvalHistoryService。
 */
@Service
public class EvalRunnerService {

    private static final Logger log = LoggerFactory.getLogger(EvalRunnerService.class);

    static final String REPORT_PATH = EvalConstants.REPORT_PATH_STR;
    private static final int EVAL_CONCURRENCY = 4;
    /** 单条评测用例最长耗时（秒），包含 query rewrite + retrieval + rerank + answer 生成。 */
    private static final long SINGLE_CASE_TIMEOUT_SECONDS = 120L;

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
        EvalConstants.requireAdmin(currentUserService);
        List<GoldenCase> goldenCases = loadGoldenSet();
        int total = goldenCases.size();
        log.info("开始评测，共 {} 条用例，并发度: {}", total, EVAL_CONCURRENCY);

        LocalDateTime generatedAt = LocalDateTime.now();
        String kbId = evalSeedService.findOrCreateEvalKb(generatedAt);

        long phase1Start = System.currentTimeMillis();
        List<EvalRaw> raws = runConcurrently("Phase 1", goldenCases, gc -> {
            ChatResponse response = askGoldenCase(kbId, gc);
            waitForTrace(response.qaTraceId());
            return new EvalRaw(response, gc);
        });
        log.info("Phase 1 完成，耗时 {} ms", System.currentTimeMillis() - phase1Start);

        long phase2Start = System.currentTimeMillis();
        List<EvalCaseResponse> results = runConcurrently("Phase 2", raws, raw -> {
            GoldenCase gc = raw.goldenCase();
            ChatResponse resp = raw.response();
            Double faithfulness = null;
            if (!gc.outOfScope() && !resp.refused() && !resp.sources().isEmpty()) {
                faithfulness = faithfulnessEvaluator.evaluate(gc.question(), resp.answer(), resp.sources());
            }
            return toCaseResponse(gc, resp, faithfulness);
        });
        log.info("Phase 2 完成，耗时 {} ms", System.currentTimeMillis() - phase2Start);

        EvalSummaryResponse summary = summarize(results);
        evalReportRenderer.renderAndWrite(generatedAt, summary, results);
        log.info("评测报告已生成: {}", REPORT_PATH);

        return new EvalRunResponse(kbId, generatedAt, summary, results, REPORT_PATH);
    }

    public EvalReportResponse latestReport() {
        EvalConstants.requireAdmin(currentUserService);
        if (!Files.exists(EvalConstants.REPORT_PATH)) {
            throw new ResourceNotFoundException("Eval report not found.");
        }
        try {
            LocalDateTime updatedAt = LocalDateTime.ofInstant(
                    Files.getLastModifiedTime(EvalConstants.REPORT_PATH).toInstant(), ZoneId.systemDefault());
            return new EvalReportResponse(updatedAt, REPORT_PATH,
                    Files.readString(EvalConstants.REPORT_PATH, StandardCharsets.UTF_8));
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to read eval report.", ex);
        }
    }

    // ── 并发执行模板 ──────────────────────────────────────

    private <T, R> List<R> runConcurrently(String phaseLabel, List<T> items, Function<T, R> task) {
        AtomicInteger completed = new AtomicInteger(0);
        int total = items.size();
        List<Future<R>> futures = new ArrayList<>();
        // 捕获主线程（HTTP 请求线程）的 SecurityContext，传播到工作线程
        SecurityContext callerCtx = SecurityContextHolder.getContext();

        for (T item : items) {
            futures.add(evalExecutor.submit(() -> {
                SecurityContext previousCtx = SecurityContextHolder.getContext();
                try {
                    SecurityContextHolder.setContext(callerCtx);
                    R result = task.apply(item);
                    log.info("[{}] 进度 {}/{}", phaseLabel, completed.incrementAndGet(), total);
                    return result;
                } finally {
                    SecurityContextHolder.setContext(previousCtx);
                }
            }));
        }

        List<R> results = new ArrayList<>();
        for (int i = 0; i < futures.size(); i++) {
            Future<R> future = futures.get(i);
            try {
                results.add(future.get(SINGLE_CASE_TIMEOUT_SECONDS, TimeUnit.SECONDS));
            } catch (TimeoutException e) {
                future.cancel(true);
                log.error("[{}] 第 {}/{} 条用例超时（>{}s），已取消", phaseLabel, i + 1, total, SINGLE_CASE_TIMEOUT_SECONDS);
                throw new IllegalStateException(
                        phaseLabel + " 第 " + (i + 1) + "/" + total + " 条用例超时（>" + SINGLE_CASE_TIMEOUT_SECONDS + "s），已取消", e);
            } catch (ExecutionException e) {
                futures.forEach(f -> f.cancel(true));
                Throwable cause = e.getCause() != null ? e.getCause() : e;
                log.error("[{}] 第 {}/{} 条用例执行失败: {}", phaseLabel, i + 1, total, cause.getMessage());
                throw new IllegalStateException(phaseLabel + " 并发执行失败: " + cause.getMessage(), cause);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                futures.forEach(f -> f.cancel(true));
                throw new IllegalStateException(phaseLabel + " 并发执行被中断", e);
            }
        }
        return results;
    }

    // ── Golden Set 加载 ────────────────────────────────────

    private List<GoldenCase> loadGoldenSet() {
        try {
            List<GoldenCase> cases = new ArrayList<>();
            for (String line : Files.readAllLines(EvalConstants.GOLDEN_SET_PATH, StandardCharsets.UTF_8)) {
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
        ChatResponse setupResponse = ask(kbId, goldenCase.setupQuestion(), null, "auto");
        waitForTrace(setupResponse.qaTraceId());
        return ask(kbId, goldenCase.question(), setupResponse.sessionId(), goldenCase.profile());
    }

    private ChatResponse ask(String kbId, String question, String sessionId, String profile) {
        return chatService.answer(kbId, new ChatRequest(question, 5, profile, sessionId));
    }

    // ── 用例评估 ──────────────────────────────────────────

    private EvalCaseResponse toCaseResponse(GoldenCase goldenCase, ChatResponse response, Double faithfulness) {
        List<String> sourceTitles = response.sources().stream().map(SourceCitation::title).toList();

        boolean documentHit = !goldenCase.outOfScope()
                && goldenCase.expectedDocTitle() != null
                && sourceTitles.contains(goldenCase.expectedDocTitle());

        boolean refusalCorrect = goldenCase.outOfScope() == response.refused();

        String answerText = response.answer();
        String matchedKeyword = goldenCase.expectedKeywords().stream()
                .filter(kw -> answerText != null && answerText.contains(kw))
                .findFirst().orElse(null);

        boolean citationHit = documentHit && matchedKeyword != null;
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
                goldenCase.id(), goldenCase.setupQuestion(), goldenCase.question(), goldenCase.profile(),
                goldenCase.outOfScope() ? EvalConstants.EXPECTED_REFUSAL : goldenCase.expectedDocTitle(),
                response.refused(), sourceTitles, documentHit, citationHit, refusalCorrect, passed,
                response.qaTraceId(), response.sessionId(),
                matchedKeyword != null, matchedKeyword, docRank, faithfulness);
    }

    // ── 指标汇总 ──────────────────────────────────────────

    private EvalSummaryResponse summarize(List<EvalCaseResponse> results) {
        List<EvalCaseResponse> inScopeResults = results.stream()
                .filter(r -> !EvalConstants.EXPECTED_REFUSAL.equals(r.expected()))
                .toList();
        int inScope = inScopeResults.size();
        int outOfScope = results.size() - inScope;

        long documentHits = inScopeResults.stream().filter(EvalCaseResponse::documentHit).count();
        double documentHitRate = inScope == 0 ? 0.0d : (double) documentHits / inScope;

        long citationHits = inScopeResults.stream().filter(EvalCaseResponse::citationHit).count();
        double citationHitRate = inScope == 0 ? 0.0d : (double) citationHits / inScope;

        long refusalCorrect = results.stream()
                .filter(r -> EvalConstants.EXPECTED_REFUSAL.equals(r.expected()) && r.refusalCorrect())
                .count();
        double refusalAccuracy = outOfScope == 0 ? 0.0d : (double) refusalCorrect / outOfScope;

        long keywordHits = inScopeResults.stream().filter(EvalCaseResponse::keywordHit).count();
        double keywordHitRate = inScopeResults.isEmpty() ? 0 : (double) keywordHits / inScope;

        double mrr = inScopeResults.stream()
                .mapToDouble(r -> r.docRank() > 0 ? 1.0 / r.docRank() : 0.0)
                .average().orElse(0);

        double meanDocRank = inScopeResults.stream()
                .mapToInt(EvalCaseResponse::docRank).filter(rank -> rank > 0)
                .average().orElse(0);

        java.util.OptionalDouble faithfulnessAvg = inScopeResults.stream()
                .filter(r -> !r.refused())
                .map(EvalCaseResponse::faithfulness)
                .filter(f -> f != null)
                .mapToDouble(Double::doubleValue)
                .average();
        Double faithfulness = faithfulnessAvg.isPresent() ? faithfulnessAvg.getAsDouble() : null;

        return new EvalSummaryResponse(results.size(), inScope, outOfScope,
                documentHitRate, citationHitRate, refusalAccuracy,
                keywordHitRate, mrr, meanDocRank, faithfulness);
    }

    // ── 等待工具 ──────────────────────────────────────────

    private void waitForTrace(String traceId) {
        for (int i = 0; i < EvalConstants.MAX_TRACE_POLLS; i++) {
            Long count = jdbcClient.sql("SELECT COUNT(*) FROM qa_traces WHERE id = :traceId")
                    .param("traceId", traceId)
                    .query(Long.class)
                    .single();
            if (count == 1) {
                return;
            }
            EvalConstants.sleep();
        }
        throw new IllegalStateException("Timed out waiting for QA trace " + traceId + ".");
    }

    // ── 内部类型 ──────────────────────────────────────────

    private record EvalRaw(ChatResponse response, GoldenCase goldenCase) {
    }

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

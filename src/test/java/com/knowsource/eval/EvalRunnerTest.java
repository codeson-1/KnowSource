package com.knowsource.eval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.knowsource.chat.AnswerGenerator;
import com.knowsource.chat.ChatResponse;
import com.knowsource.index.DocumentEmbeddingGateway;
import com.knowsource.index.DocumentIndexOutboxService;
import com.knowsource.user.DemoUserInitializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("db")
@WithMockUser(username = "demo", roles = "ADMIN")
class EvalRunnerTest {

    private static final Path GOLDEN_SET = Path.of("docs/eval/golden-set.jsonl");
    private static final Path REPORT = Path.of("docs/eval/report.md");
    private static final String EXPECTED_REFUSAL = "拒答";

    @org.springframework.beans.factory.annotation.Autowired
    private MockMvc mockMvc;

    @org.springframework.beans.factory.annotation.Autowired
    private JdbcClient jdbcClient;

    @org.springframework.beans.factory.annotation.Autowired
    private DemoUserInitializer demoUserInitializer;

    @org.springframework.beans.factory.annotation.Autowired
    private ObjectMapper objectMapper;

    @org.springframework.beans.factory.annotation.Autowired
    private DocumentIndexOutboxService indexOutboxService;

    @BeforeEach
    void cleanBusinessData() {
        jdbcClient.sql("DELETE FROM qa_traces").update();
        jdbcClient.sql("DELETE FROM chat_messages").update();
        jdbcClient.sql("DELETE FROM chat_sessions").update();
        jdbcClient.sql("DELETE FROM document_publish_events").update();
        jdbcClient.sql("DELETE FROM vector_store").update();
        jdbcClient.sql("DELETE FROM chunk_children").update();
        jdbcClient.sql("DELETE FROM chunk_parents").update();
        jdbcClient.sql("DELETE FROM ingest_tasks").update();
        jdbcClient.sql("DELETE FROM documents").update();
        jdbcClient.sql("DELETE FROM kb_members").update();
        jdbcClient.sql("DELETE FROM knowledge_bases").update();
        jdbcClient.sql("DELETE FROM refresh_tokens").update();
        jdbcClient.sql("DELETE FROM users").update();
        demoUserInitializer.run(null);
    }

    @Test
    void runsGoldenSetAndWritesReport() throws Exception {
        String kbId = createKnowledgeBase("Eval KB");
        seedAndPublishDocuments(kbId);
        List<GoldenCase> cases = loadGoldenSet();
        List<EvalCaseResult> results = new ArrayList<>();

        for (GoldenCase goldenCase : cases) {
            ChatResponse response = askGoldenCase(kbId, goldenCase);
            results.add(EvalCaseResult.from(goldenCase, response));
        }

        EvalSummary summary = EvalSummary.from(results);
        Files.createDirectories(REPORT.getParent());
        Files.writeString(REPORT, renderReport(summary, results), StandardCharsets.UTF_8);

        assertThat(summary.recallAt5()).isGreaterThanOrEqualTo(0.80d);
        assertThat(summary.refusalAccuracy()).isEqualTo(1.0d);
        assertThat(summary.citationHitRate()).isGreaterThanOrEqualTo(0.80d);
        assertThat(Files.exists(REPORT)).isTrue();
    }

    private void seedAndPublishDocuments(String kbId) throws Exception {
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

    private List<GoldenCase> loadGoldenSet() throws Exception {
        List<GoldenCase> cases = new ArrayList<>();
        for (String line : Files.readAllLines(GOLDEN_SET, StandardCharsets.UTF_8)) {
            if (!line.isBlank()) {
                cases.add(objectMapper.readValue(line, GoldenCase.class));
            }
        }
        return cases;
    }

    private String createKnowledgeBase(String name) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/kbs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).path("id").asText();
    }

    private String createDocument(String kbId, String title, String content) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/kbs/{kbId}/documents", kbId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateDocumentPayload(title, content))))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", startsWith("/api/documents/")))
                .andReturn();
        String docId = objectMapper.readTree(result.getResponse().getContentAsString())
                .path("document")
                .path("id")
                .asText();
        waitForIngestReady(docId);
        return docId;
    }

    private void publishDocument(String docId) throws Exception {
        mockMvc.perform(post("/api/documents/{docId}/publish", docId))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.indexStatus").value("PENDING"));
        assertThat(indexOutboxService.processNextPendingEvent()).isTrue();
    }

    private ChatResponse ask(String kbId, String question) throws Exception {
        return ask(kbId, question, null, null);
    }

    private ChatResponse askGoldenCase(String kbId, GoldenCase goldenCase) throws Exception {
        if (goldenCase.setupQuestion() == null || goldenCase.setupQuestion().isBlank()) {
            return ask(kbId, goldenCase.question(), null, goldenCase.profile());
        }

        ChatResponse setupResponse = ask(kbId, goldenCase.setupQuestion(), null, "auto");
        return ask(kbId, goldenCase.question(), setupResponse.sessionId(), goldenCase.profile());
    }

    private ChatResponse ask(String kbId, String question, String sessionId, String profile) throws Exception {
        String optionalSession = sessionId == null ? "" : """
                                  ,"sessionId": "%s"
                """.formatted(sessionId);
        String optionalProfile = profile == null ? "" : """
                                  ,"profile": "%s"
                """.formatted(profile);
        MvcResult result = mockMvc.perform(post("/api/kbs/{kbId}/chat", kbId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "question": "%s",
                                  "topK": 5%s%s
                                }
                                """.formatted(question, optionalSession, optionalProfile)))
                .andExpect(status().isOk())
                .andReturn();
        ChatResponse response = objectMapper.readValue(result.getResponse().getContentAsString(), ChatResponse.class);
        waitForTrace(response.qaTraceId());
        return response;
    }

    private void waitForIngestReady(String docId) throws InterruptedException {
        for (int i = 0; i < 40; i++) {
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
            Thread.sleep(50);
        }
        fail("Timed out waiting for ingest task READY for " + docId);
    }

    private void waitForTrace(String traceId) throws InterruptedException {
        for (int i = 0; i < 20; i++) {
            Long count = jdbcClient.sql("SELECT COUNT(*) FROM qa_traces WHERE id = :traceId")
                    .param("traceId", traceId)
                    .query(Long.class)
                    .single();
            if (count == 1) {
                return;
            }
            Thread.sleep(50);
        }
        fail("Timed out waiting for QA trace " + traceId);
    }

    private static String renderReport(EvalSummary summary, List<EvalCaseResult> results) {
        StringBuilder report = new StringBuilder();
        report.append("# KnowSource 评测报告\n\n");
        report.append("生成时间: ").append(LocalDateTime.now()).append("\n\n");
        report.append("| 指标 | 数值 |\n");
        report.append("|---|---:|\n");
        report.append("| 用例总数 | ").append(summary.totalCases()).append(" |\n");
        report.append("| 范围内用例 | ").append(summary.inScopeCases()).append(" |\n");
        report.append("| 范围外用例 | ").append(summary.outOfScopeCases()).append(" |\n");
        report.append("| Recall@5 | ").append(formatPercent(summary.recallAt5())).append(" |\n");
        report.append("| 引用命中率 | ").append(formatPercent(summary.citationHitRate())).append(" |\n");
        report.append("| 拒答准确率 | ").append(formatPercent(summary.refusalAccuracy())).append(" |\n\n");
        report.append("## 用例结果\n\n");
        report.append("| 用例 ID | 前置问题 | 问题 | 期望 | 是否拒答 | 来源文档 | 是否通过 |\n");
        report.append("|---|---|---|---|---:|---|---:|\n");
        for (EvalCaseResult result : results) {
            report.append("| ")
                    .append(result.id()).append(" | ")
                    .append(escape(result.setupQuestion())).append(" | ")
                    .append(escape(result.question())).append(" | ")
                    .append(result.expected()).append(" | ")
                    .append(result.refused() ? "是" : "否").append(" | ")
                    .append(escape(String.join(", ", result.sourceTitles()))).append(" | ")
                    .append(result.passed() ? "是" : "否")
                    .append(" |\n");
        }
        return report.toString();
    }

    private static String escape(String value) {
        return value == null ? "" : value.replace("|", "\\|").replace("\n", " ");
    }

    private static String formatPercent(double value) {
        return "%.1f%%".formatted(value * 100.0d);
    }

    record GoldenCase(
            String id,
            String setupQuestion,
            String question,
            String profile,
            String expectedDocTitle,
            List<String> expectedKeywords,
            boolean outOfScope) {
    }

    record CreateDocumentPayload(String title, String content) {
    }

    record EvalCaseResult(
            String id,
            String setupQuestion,
            String question,
            String expected,
            boolean refused,
            List<String> sourceTitles,
            boolean citationHit,
            boolean refusalCorrect,
            boolean passed) {

        static EvalCaseResult from(GoldenCase goldenCase, ChatResponse response) {
            List<String> sourceTitles = response.sources().stream()
                    .map(source -> source.title())
                    .toList();
            boolean citationHit = !goldenCase.outOfScope()
                    && goldenCase.expectedDocTitle() != null
                    && sourceTitles.contains(goldenCase.expectedDocTitle());
            boolean refusalCorrect = goldenCase.outOfScope() == response.refused();
            boolean passed = goldenCase.outOfScope() ? refusalCorrect : citationHit && !response.refused();
            return new EvalCaseResult(
                    goldenCase.id(),
                    goldenCase.setupQuestion(),
                    goldenCase.question(),
                    goldenCase.outOfScope() ? EXPECTED_REFUSAL : goldenCase.expectedDocTitle(),
                    response.refused(),
                    sourceTitles,
                    citationHit,
                    refusalCorrect,
                    passed);
        }
    }

    record EvalSummary(
            int totalCases,
            int inScopeCases,
            int outOfScopeCases,
            double recallAt5,
            double citationHitRate,
            double refusalAccuracy) {

        static EvalSummary from(List<EvalCaseResult> results) {
            int inScope = (int) results.stream().filter(result -> !EXPECTED_REFUSAL.equals(result.expected())).count();
            int outOfScope = results.size() - inScope;
            long citationHits = results.stream().filter(EvalCaseResult::citationHit).count();
            long refusalCorrect = results.stream()
                    .filter(result -> EXPECTED_REFUSAL.equals(result.expected()))
                    .filter(EvalCaseResult::refusalCorrect)
                    .count();
            return new EvalSummary(
                    results.size(),
                    inScope,
                    outOfScope,
                    inScope == 0 ? 0.0d : (double) citationHits / inScope,
                    inScope == 0 ? 0.0d : (double) citationHits / inScope,
                    outOfScope == 0 ? 0.0d : (double) refusalCorrect / outOfScope);
        }
    }

    @TestConfiguration
    static class EvalEmbeddingConfig {

        @Bean
        DocumentEmbeddingGateway documentEmbeddingGateway() {
            return new DocumentEmbeddingGateway() {
                @Override
                public List<float[]> embed(List<String> texts) {
                    return embedDocuments(texts);
                }

                @Override
                public List<float[]> embedDocuments(List<String> texts) {
                    return texts.stream().map(EvalEmbeddingConfig::embedding).toList();
                }

                @Override
                public List<float[]> embedQuery(String text) {
                    return List.of(embedding(text));
                }
            };
        }

        @Bean
        AnswerGenerator answerGenerator() {
            return (question, sources) -> "评测回答：" + question;
        }

        private static float[] embedding(String text) {
            Set<String> categories = categories(text);
            float[] embedding = new float[1024];
            if (categories.contains("leave")) {
                embedding[0] = 1.0f;
            }
            if (categories.contains("security")) {
                embedding[1] = 1.0f;
            }
            if (categories.contains("expense")) {
                embedding[2] = 1.0f;
            }
            if (categories.contains("remote")) {
                embedding[3] = 1.0f;
            }
            if (categories.isEmpty()) {
                embedding[10] = 1.0f;
            }
            return embedding;
        }

        private static Set<String> categories(String text) {
            String normalized = text.toLowerCase(Locale.ROOT);
            java.util.LinkedHashSet<String> categories = new java.util.LinkedHashSet<>();
            if (containsAny(normalized,
                    "leave", "annual", "approve", "approval", "manager",
                    "年假", "假期", "休年假", "直属经理", "hr", "复核", "全职")) {
                categories.add("leave");
            }
            if (containsAny(normalized, "carryover", "unused", "结转", "未使用")) {
                categories.add("leave");
            }
            if (containsAny(normalized,
                    "security", "badge", "office", "visitor", "lost",
                    "安全", "工牌", "办公区", "访客", "前台", "丢失", "门禁")) {
                categories.add("security");
            }
            if (containsAny(normalized, "incident", "24 hours", "事件", "24 小时", "24小时", "上报")) {
                categories.add("security");
            }
            if (containsAny(normalized,
                    "expense", "reimbursement", "receipt", "finance", "lodging",
                    "报销", "票据", "财务", "住宿", "额度", "餐费", "交通")) {
                categories.add("expense");
            }
            if (containsAny(normalized, "limit", "800")) {
                categories.add("expense");
            }
            if (containsAny(normalized,
                    "remote", "work", "week",
                    "远程", "办公", "每周", "团队负责人", "线下培训")) {
                categories.add("remote");
            }
            return categories;
        }

        private static boolean containsAny(String text, String... terms) {
            for (String term : terms) {
                if (text.contains(term)) {
                    return true;
                }
            }
            return false;
        }
    }
}

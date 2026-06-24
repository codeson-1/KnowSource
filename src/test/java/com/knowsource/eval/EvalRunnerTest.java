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
        publishDocument(createDocument(kbId, "Annual Leave Policy",
                """
                        # Annual Leave Policy

                        ## Entitlement

                        Annual leave is 10 days for full-time employees. Carryover is allowed for up to 5 unused days into the next calendar year.

                        ## Approval Workflow

                        Annual leave requires manager approval before the planned absence. Requests longer than 5 consecutive days also require HR review.
                        """));
        publishDocument(createDocument(kbId, "Security Policy",
                """
                        # Security Policy

                        ## Office Access

                        Security badges are required in the office. Visitors must register at reception and wear a visitor badge.

                        ## Incident Reporting

                        A lost badge must be reported to security within 24 hours so the access card can be disabled.
                        """));
        publishDocument(createDocument(kbId, "Expense Policy",
                """
                        # Expense Policy

                        ## Submission Deadline

                        Reimbursement receipts should be submitted within 30 days through the finance portal.

                        ## Reimbursement Limits

                        | Category | Limit |
                        | --- | --- |
                        | Meal | 120 |
                        | Lodging | 800 |
                        | Local transport | 300 |
                        """));
        publishDocument(createDocument(kbId, "Remote Work Policy",
                """
                        # Remote Work Policy

                        Employees may work remotely 2 days each week after team lead approval. Remote work should not be used on mandatory on-site training days.
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
        report.append("# KnowSource Eval Report\n\n");
        report.append("Generated at: ").append(LocalDateTime.now()).append("\n\n");
        report.append("| Metric | Value |\n");
        report.append("|---|---:|\n");
        report.append("| Total cases | ").append(summary.totalCases()).append(" |\n");
        report.append("| In-scope cases | ").append(summary.inScopeCases()).append(" |\n");
        report.append("| Out-of-scope cases | ").append(summary.outOfScopeCases()).append(" |\n");
        report.append("| Recall@5 | ").append(formatPercent(summary.recallAt5())).append(" |\n");
        report.append("| Citation hit rate | ").append(formatPercent(summary.citationHitRate())).append(" |\n");
        report.append("| Refusal accuracy | ").append(formatPercent(summary.refusalAccuracy())).append(" |\n\n");
        report.append("## Case Results\n\n");
        report.append("| ID | Setup | Question | Expected | Refused | Source titles | Pass |\n");
        report.append("|---|---|---|---|---:|---|---:|\n");
        for (EvalCaseResult result : results) {
            report.append("| ")
                    .append(result.id()).append(" | ")
                    .append(escape(result.setupQuestion())).append(" | ")
                    .append(escape(result.question())).append(" | ")
                    .append(result.expected()).append(" | ")
                    .append(result.refused()).append(" | ")
                    .append(escape(String.join(", ", result.sourceTitles()))).append(" | ")
                    .append(result.passed() ? "yes" : "no")
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
                    goldenCase.outOfScope() ? "REFUSAL" : goldenCase.expectedDocTitle(),
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
            int inScope = (int) results.stream().filter(result -> !"REFUSAL".equals(result.expected())).count();
            int outOfScope = results.size() - inScope;
            long citationHits = results.stream().filter(EvalCaseResult::citationHit).count();
            long refusalCorrect = results.stream()
                    .filter(result -> "REFUSAL".equals(result.expected()))
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
            if (containsAny(normalized, "leave", "annual", "approve", "approval", "manager")) {
                categories.add("leave");
            }
            if (containsAny(normalized, "carryover", "unused")) {
                categories.add("leave");
            }
            if (containsAny(normalized, "security", "badge", "office", "visitor", "lost")) {
                categories.add("security");
            }
            if (containsAny(normalized, "incident", "24 hours")) {
                categories.add("security");
            }
            if (containsAny(normalized, "expense", "reimbursement", "receipt", "finance", "lodging")) {
                categories.add("expense");
            }
            if (containsAny(normalized, "limit", "800")) {
                categories.add("expense");
            }
            if (containsAny(normalized, "remote", "work", "week")) {
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

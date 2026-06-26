package com.knowsource.eval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import com.knowsource.chat.AnswerGenerator;
import com.knowsource.index.DocumentEmbeddingGateway;
import com.knowsource.user.DemoUserInitializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("db")
@WithMockUser(username = "demo", roles = "ADMIN")
class EvalControllerTest {

    @org.springframework.beans.factory.annotation.Autowired
    private MockMvc mockMvc;

    @org.springframework.beans.factory.annotation.Autowired
    private JdbcClient jdbcClient;

    @org.springframework.beans.factory.annotation.Autowired
    private DemoUserInitializer demoUserInitializer;

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
    void adminRunsGoldenSetAndReadsReport() throws Exception {
        mockMvc.perform(post("/api/eval/golden-set/run"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kbId").isNotEmpty())
                .andExpect(jsonPath("$.summary.totalCases").value(15))
                .andExpect(jsonPath("$.summary.recallAt5").value(1.0d))
                .andExpect(jsonPath("$.summary.refusalAccuracy").value(1.0d))
                .andExpect(jsonPath("$.cases[0].qaTraceId").isNotEmpty())
                .andExpect(jsonPath("$.reportPath").value("docs/eval/report.md"));

        Long traceRows = jdbcClient.sql("SELECT COUNT(*) FROM qa_traces")
                .query(Long.class)
                .single();
        assertThat(traceRows).isGreaterThanOrEqualTo(15);

        mockMvc.perform(get("/api/eval/golden-set/report"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.markdown").value(org.hamcrest.Matchers.containsString("KnowSource Eval Report")));
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
            return (question, sources) -> "Eval answer for: " + question;
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

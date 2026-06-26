package com.knowsource.chat;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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

/**
 * Verifies the B-1 fix: when no LLM answer generator is configured (model API key missing),
 * the chat endpoint must fail explicitly instead of returning a silent placeholder answer.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("db")
@WithMockUser(username = "demo", roles = "ADMIN")
class ChatLlmUnavailableTest {

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
    void nonStreamingReturnsExplicitErrorWhenLlmUnavailable() throws Exception {
        String kbId = createKnowledgeBase("LLM Unavailable KB");
        String docId = createDocument(kbId, "Leave Policy", "Annual leave is 10 days. Approval is required.");
        publishDocument(docId);

        mockMvc.perform(post("/api/kbs/{kbId}/chat", kbId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "question": "How many annual leave days are available?"
                                }
                                """))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value(LlmUnavailableException.ERROR_CODE))
                .andExpect(jsonPath("$.message", containsString("Answer generator is not available")));
    }

    @Test
    void streamEmitsErrorEventWhenLlmUnavailable() throws Exception {
        String kbId = createKnowledgeBase("LLM Unavailable Stream KB");
        String docId = createDocument(kbId, "Leave Policy", "Annual leave is 10 days. Approval is required.");
        publishDocument(docId);

        MvcResult result = mockMvc.perform(post("/api/kbs/{kbId}/chat/stream", kbId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .content("""
                                {
                                  "question": "How many annual leave days are available?"
                                }
                                """))
                .andExpect(request().asyncStarted())
                .andReturn();

        mockMvc.perform(asyncDispatch(result))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM))
                .andExpect(content().string(containsString("event:error")))
                .andExpect(content().string(containsString("\"code\":" + LlmUnavailableException.ERROR_CODE)))
                .andExpect(content().string(containsString("\"qaTraceId\"")));
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
                        .content("""
                                {
                                  "title": "%s",
                                  "content": "%s"
                                }
                                """.formatted(title, content)))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        String docId = body.path("document").path("id").asText();
        waitForIngestReady(docId);
        return docId;
    }

    private void publishDocument(String docId) throws Exception {
        mockMvc.perform(post("/api/documents/{docId}/publish", docId))
                .andExpect(status().isAccepted());
        org.assertj.core.api.Assertions.assertThat(indexOutboxService.processNextPendingEvent()).isTrue();
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
        throw new IllegalStateException("Timed out waiting for ingest task READY for " + docId);
    }

    /**
     * Provides an embedding gateway so retrieval works, but intentionally does NOT register any
     * {@link AnswerGenerator} or {@link StreamingAnswerGenerator}, simulating a deployment where
     * the model API key is missing.
     */
    @TestConfiguration
    static class NoAnswerGeneratorConfig {

        @Bean
        DocumentEmbeddingGateway documentEmbeddingGateway() {
            return new DocumentEmbeddingGateway() {
                @Override
                public List<float[]> embed(List<String> texts) {
                    return embedDocuments(texts);
                }

                @Override
                public List<float[]> embedDocuments(List<String> texts) {
                    return texts.stream().map(NoAnswerGeneratorConfig::embedding).toList();
                }

                @Override
                public List<float[]> embedQuery(String text) {
                    return embedDocuments(List.of(text));
                }
            };
        }

        private static float[] embedding(String text) {
            float[] embedding = new float[1024];
            if (text.toLowerCase().contains("leave")) {
                embedding[0] = 1.0f;
            }
            return embedding;
        }
    }
}

package com.knowsource.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.knowsource.index.DocumentEmbeddingGateway;
import com.knowsource.user.DemoUserInitializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("db")
class ChatControllerTest {

    @org.springframework.beans.factory.annotation.Autowired
    private MockMvc mockMvc;

    @org.springframework.beans.factory.annotation.Autowired
    private JdbcClient jdbcClient;

    @org.springframework.beans.factory.annotation.Autowired
    private DemoUserInitializer demoUserInitializer;

    @org.springframework.beans.factory.annotation.Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void cleanBusinessData() {
        jdbcClient.sql("DELETE FROM qa_traces").update();
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
    void answersWithSourcesFromSyncedPublishedVectors() throws Exception {
        String kbId = createKnowledgeBase("HR KB");
        String leaveDocId = createDocument(kbId, "Leave Policy", "Annual leave is 10 days. Approval is required.");
        createDocument(kbId, "Security Policy", "Security badges are required in the office.");

        publishDocument(leaveDocId);

        MvcResult result = mockMvc.perform(post("/api/kbs/{kbId}/chat", kbId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "question": "How many annual leave days are available?",
                                  "topK": 3
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.qaTraceId").value(not("")))
                .andExpect(jsonPath("$.kbId").value(kbId))
                .andExpect(jsonPath("$.question").value("How many annual leave days are available?"))
                .andExpect(jsonPath("$.refused").value(false))
                .andExpect(jsonPath("$.answer").value(startsWith("已检索到相关知识片段")))
                .andExpect(jsonPath("$.sources", hasSize(1)))
                .andExpect(jsonPath("$.sources[0].index").value(1))
                .andExpect(jsonPath("$.sources[0].docId").value(leaveDocId))
                .andExpect(jsonPath("$.sources[0].docVersion").value(1))
                .andExpect(jsonPath("$.sources[0].title").value("Leave Policy"))
                .andExpect(jsonPath("$.sources[0].snippet").value("Annual leave is 10 days. Approval is required."))
                .andReturn();

        String traceId = objectMapper.readTree(result.getResponse().getContentAsString()).path("qaTraceId").asText();
        waitForTrace(traceId);

        Long traceCount = jdbcClient.sql("""
                SELECT COUNT(*)
                FROM qa_traces
                WHERE id = :traceId
                  AND kb_id = :kbId
                  AND query = 'How many annual leave days are available?'
                  AND answer LIKE '已检索到相关知识片段%'
                  AND rag_profile = 'naive'
                  AND retrieval_ms IS NOT NULL
                  AND llm_ms = 0
                  AND total_ms IS NOT NULL
                  AND jsonb_array_length(retrieved_chunks) = 1
                  AND retrieved_chunks -> 0 ->> 'docId' = :docId
                """)
                .param("traceId", traceId)
                .param("kbId", kbId)
                .param("docId", leaveDocId)
                .query(Long.class)
                .single();
        assertThat(traceCount).isEqualTo(1);
    }

    @Test
    void streamsSourcesTokensAndDoneEvent() throws Exception {
        String kbId = createKnowledgeBase("Stream KB");
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

        MvcResult dispatched = mockMvc.perform(asyncDispatch(result))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("event:sources")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("\"docId\":\"" + docId + "\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("event:token")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("event:done")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("\"qaTraceId\":\"")))
                .andReturn();

        String traceId = extractTraceId(dispatched.getResponse().getContentAsString());
        waitForTrace(traceId);

        Long tracedStreams = jdbcClient.sql("""
                SELECT COUNT(*)
                FROM qa_traces
                WHERE id = :traceId
                  AND kb_id = :kbId
                  AND generation_first_token_ms IS NOT NULL
                  AND jsonb_array_length(retrieved_chunks) = 1
                """)
                .param("traceId", traceId)
                .param("kbId", kbId)
                .query(Long.class)
                .single();
        assertThat(tracedStreams).isEqualTo(1);
    }

    @Test
    void getsQaTraceDetailByTraceId() throws Exception {
        String kbId = createKnowledgeBase("Trace Detail KB");
        String docId = createDocument(kbId, "Leave Policy", "Annual leave is 10 days. Approval is required.");
        publishDocument(docId);
        String traceId = askQuestion(kbId, "How many annual leave days are available?");

        mockMvc.perform(get("/api/kbs/{kbId}/qa-traces/{traceId}", kbId, traceId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(traceId))
                .andExpect(jsonPath("$.kbId").value(kbId))
                .andExpect(jsonPath("$.query").value("How many annual leave days are available?"))
                .andExpect(jsonPath("$.answer").isNotEmpty())
                .andExpect(jsonPath("$.retrievedChunks", hasSize(1)))
                .andExpect(jsonPath("$.retrievedChunks[0].docId").value(docId))
                .andExpect(jsonPath("$.retrievalMs").isNumber())
                .andExpect(jsonPath("$.llmMs").value(0))
                .andExpect(jsonPath("$.totalMs").isNumber())
                .andExpect(jsonPath("$.tokenUsage").isMap())
                .andExpect(jsonPath("$.ragProfile").value("naive"))
                .andExpect(jsonPath("$.createdAt").isNotEmpty());
    }

    @Test
    void listsRecentQaTracesByKnowledgeBase() throws Exception {
        String kbId = createKnowledgeBase("Trace List KB");
        String docId = createDocument(kbId, "Leave Policy", "Annual leave is 10 days. Approval is required.");
        publishDocument(docId);
        String olderTraceId = askQuestion(kbId, "How many annual leave days are available?");
        String newerTraceId = askQuestion(kbId, "Tell me about annual leave.");

        mockMvc.perform(get("/api/kbs/{kbId}/qa-traces?limit=1", kbId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].id").value(newerTraceId))
                .andExpect(jsonPath("$[0].kbId").value(kbId))
                .andExpect(jsonPath("$[0].query").value("Tell me about annual leave."))
                .andExpect(jsonPath("$[0].answerPreview").isNotEmpty())
                .andExpect(jsonPath("$[0].sourceCount").value(1))
                .andExpect(jsonPath("$[0].totalMs").isNumber())
                .andExpect(jsonPath("$[0].ragProfile").value("naive"))
                .andExpect(jsonPath("$[0].createdAt").isNotEmpty());

        assertThat(olderTraceId).isNotEqualTo(newerTraceId);
    }

    @Test
    void rejectsInvalidQaTraceListLimit() throws Exception {
        String kbId = createKnowledgeBase("Trace Validation KB");

        mockMvc.perform(get("/api/kbs/{kbId}/qa-traces?limit=101", kbId))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("limit must be between 1 and 100."));
    }

    @Test
    void returnsNotFoundForMissingQaTrace() throws Exception {
        String kbId = createKnowledgeBase("Missing Trace KB");

        mockMvc.perform(get("/api/kbs/{kbId}/qa-traces/{traceId}", kbId, "missing-trace"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("QA trace not found."));
    }

    @Test
    void streamsExplicitRefusalWhenContextIsEmpty() throws Exception {
        String kbId = createKnowledgeBase("Empty Stream KB");

        MvcResult result = mockMvc.perform(post("/api/kbs/{kbId}/chat/stream", kbId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .content("""
                                {
                                  "question": "annual leave"
                                }
                                """))
                .andExpect(request().asyncStarted())
                .andReturn();

        mockMvc.perform(asyncDispatch(result))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("event:sources")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("data:[]")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("event:token")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("event:done")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("\"refused\":true")));
    }

    @Test
    void refusesWhenOnlyDraftDocumentMatches() throws Exception {
        String kbId = createKnowledgeBase("Draft KB");
        createDocument(kbId, "Draft Leave Policy", "Annual leave is 12 days in this draft.");

        mockMvc.perform(post("/api/kbs/{kbId}/chat", kbId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "question": "annual leave"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.refused").value(true))
                .andExpect(jsonPath("$.answer").value("知识库中未找到相关信息。"))
                .andExpect(jsonPath("$.sources", hasSize(0)));
    }

    @Test
    void ignoresPublishedVectorsWhenDocumentIsNotSynced() throws Exception {
        String kbId = createKnowledgeBase("Indexing KB");
        String docId = createDocument(kbId, "Leave Policy", "Annual leave is 10 days. Approval is required.");
        publishDocument(docId);

        jdbcClient.sql("UPDATE documents SET index_status = 'SYNCING' WHERE id = :docId")
                .param("docId", docId)
                .update();

        mockMvc.perform(post("/api/kbs/{kbId}/chat", kbId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "question": "annual leave"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.refused").value(true))
                .andExpect(jsonPath("$.sources", hasSize(0)));
    }

    @Test
    void refusesWhenNearestVectorIsBelowSimilarityThreshold() throws Exception {
        String kbId = createKnowledgeBase("Threshold KB");
        String docId = createDocument(kbId, "Security Policy", "Security badges are required in the office.");
        publishDocument(docId);

        mockMvc.perform(post("/api/kbs/{kbId}/chat", kbId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "question": "annual leave"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.refused").value(true))
                .andExpect(jsonPath("$.sources", hasSize(0)));
    }

    @Test
    void rejectsBlankQuestion() throws Exception {
        String kbId = createKnowledgeBase("Validation KB");

        mockMvc.perform(post("/api/kbs/{kbId}/chat", kbId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "question": "   "
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Question is required."));
    }

    @Test
    void rejectsOutOfRangeTopK() throws Exception {
        String kbId = createKnowledgeBase("Validation KB");

        mockMvc.perform(post("/api/kbs/{kbId}/chat", kbId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "question": "annual leave",
                                  "topK": 16
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("topK must be between 1 and 15."));
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
                .andExpect(header().string("Location", startsWith("/api/documents/")))
                .andReturn();

        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        return body.path("document").path("id").asText();
    }

    private void publishDocument(String docId) throws Exception {
        mockMvc.perform(post("/api/documents/{docId}/publish", docId))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.indexStatus").value("SYNCED"));
    }

    private String askQuestion(String kbId, String question) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/kbs/{kbId}/chat", kbId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "question": "%s"
                                }
                                """.formatted(question)))
                .andExpect(status().isOk())
                .andReturn();
        String traceId = objectMapper.readTree(result.getResponse().getContentAsString()).path("qaTraceId").asText();
        waitForTrace(traceId);
        return traceId;
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

    private static String extractTraceId(String sseBody) {
        Matcher matcher = Pattern.compile("\"qaTraceId\":\"([^\"]+)\"").matcher(sseBody);
        assertThat(matcher.find()).isTrue();
        return matcher.group(1);
    }

    @TestConfiguration
    static class FakeEmbeddingConfig {

        @Bean
        DocumentEmbeddingGateway documentEmbeddingGateway() {
            return texts -> texts.stream()
                    .map(FakeEmbeddingConfig::embedding)
                    .toList();
        }

        private static float[] embedding(String text) {
            float[] embedding = new float[1024];
            String normalized = text.toLowerCase();
            if (normalized.contains("leave")) {
                embedding[0] = 1.0f;
            } else if (normalized.contains("security")) {
                embedding[1] = 1.0f;
            } else {
                embedding[2] = 1.0f;
            }
            return embedding;
        }
    }
}

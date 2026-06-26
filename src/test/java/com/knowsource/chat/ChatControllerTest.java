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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.knowsource.ai.AiProviderException;
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
class ChatControllerTest {

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
        FakeEmbeddingConfig.reset();
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
                .andExpect(jsonPath("$.ragProfile").value("naive"))
                .andExpect(jsonPath("$.refused").value(false))
                .andExpect(jsonPath("$.answer").value(startsWith("Generated answer for:")))
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
                  AND answer LIKE 'Generated answer for:%'
                  AND rag_profile = 'naive'
                  AND retrieval_ms IS NOT NULL
                  AND llm_ms IS NOT NULL
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
        assertThat(FakeEmbeddingConfig.queryEmbeddingCalls()).isGreaterThanOrEqualTo(1);
        assertThat(FakeEmbeddingConfig.documentEmbeddingCalls()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void routesExplicitAutoProfileToNaiveForCurrentSingleTurnFlow() throws Exception {
        String kbId = createKnowledgeBase("Profile KB");
        String docId = createDocument(kbId, "Leave Policy", "Annual leave is 10 days. Approval is required.");
        publishDocument(docId);

        MvcResult result = mockMvc.perform(post("/api/kbs/{kbId}/chat", kbId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "question": "How many annual leave days are available?",
                                  "profile": "auto"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ragProfile").value("naive"))
                .andExpect(jsonPath("$.sources", hasSize(1)))
                .andReturn();

        String traceId = objectMapper.readTree(result.getResponse().getContentAsString()).path("qaTraceId").asText();
        waitForTrace(traceId);

        String ragProfile = jdbcClient.sql("SELECT rag_profile FROM qa_traces WHERE id = :traceId")
                .param("traceId", traceId)
                .query(String.class)
                .single();
        assertThat(ragProfile).isEqualTo("naive");
    }

    @Test
    void createsChatSessionAndPersistsConversationMessages() throws Exception {
        String kbId = createKnowledgeBase("Session KB");
        String docId = createDocument(kbId, "Leave Policy", "Annual leave is 10 days. Approval is required.");
        publishDocument(docId);

        MvcResult result = mockMvc.perform(post("/api/kbs/{kbId}/chat", kbId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "question": "How many annual leave days are available?"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionId").value(not("")))
                .andExpect(jsonPath("$.rewrittenQuery").doesNotExist())
                .andExpect(jsonPath("$.ragProfile").value("naive"))
                .andReturn();

        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        String sessionId = body.path("sessionId").asText();
        String traceId = body.path("qaTraceId").asText();
        waitForTrace(traceId);

        Long sessionCount = jdbcClient.sql("""
                SELECT COUNT(*)
                FROM chat_sessions
                WHERE id = :sessionId AND kb_id = :kbId
                """)
                .param("sessionId", sessionId)
                .param("kbId", kbId)
                .query(Long.class)
                .single();
        Long messageCount = jdbcClient.sql("""
                SELECT COUNT(*)
                FROM chat_messages
                WHERE session_id = :sessionId
                """)
                .param("sessionId", sessionId)
                .query(Long.class)
                .single();
        String traceSessionId = jdbcClient.sql("SELECT session_id FROM qa_traces WHERE id = :traceId")
                .param("traceId", traceId)
                .query(String.class)
                .single();

        assertThat(sessionCount).isEqualTo(1);
        assertThat(messageCount).isEqualTo(2);
        assertThat(traceSessionId).isEqualTo(sessionId);
    }

    @Test
    void routesFollowUpWithSessionHistoryToModularAndStoresRewrittenQuery() throws Exception {
        String kbId = createKnowledgeBase("Multi Turn KB");
        String leaveDocId = createDocument(kbId, "Leave Policy", "Annual leave is 10 days. Approval is required.");
        String securityDocId = createDocument(kbId, "Security Policy", "Security badges are required in the office.");
        publishDocument(leaveDocId);
        publishDocument(securityDocId);

        MvcResult first = mockMvc.perform(post("/api/kbs/{kbId}/chat", kbId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "question": "How many annual leave days are available?",
                                  "profile": "auto"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ragProfile").value("naive"))
                .andExpect(jsonPath("$.sources[0].docId").value(leaveDocId))
                .andReturn();
        String sessionId = objectMapper.readTree(first.getResponse().getContentAsString()).path("sessionId").asText();

        MvcResult followUp = mockMvc.perform(post("/api/kbs/{kbId}/chat", kbId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "question": "What is its approval process?",
                                  "profile": "auto",
                                  "sessionId": "%s"
                                }
                                """.formatted(sessionId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionId").value(sessionId))
                .andExpect(jsonPath("$.question").value("What is its approval process?"))
                .andExpect(jsonPath("$.rewrittenQuery").value("How many annual leave days are available? What is its approval process?"))
                .andExpect(jsonPath("$.ragProfile").value("modular"))
                .andExpect(jsonPath("$.sources[0].docId").value(leaveDocId))
                .andReturn();

        String traceId = objectMapper.readTree(followUp.getResponse().getContentAsString()).path("qaTraceId").asText();
        waitForTrace(traceId);

        JsonNode trace = objectMapper.readTree(mockMvc.perform(get("/api/kbs/{kbId}/qa-traces/{traceId}", kbId, traceId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionId").value(sessionId))
                .andExpect(jsonPath("$.query").value("What is its approval process?"))
                .andExpect(jsonPath("$.rewrittenQuery").value("How many annual leave days are available? What is its approval process?"))
                .andExpect(jsonPath("$.ragProfile").value("modular"))
                .andReturn()
                .getResponse()
                .getContentAsString());
        assertThat(trace.path("retrievedChunks").get(0).path("docId").asText()).isEqualTo(leaveDocId);
    }

    @Test
    void modularProfileRunsTwoRetrievalQueriesAndMergesCandidates() throws Exception {
        String kbId = createKnowledgeBase("Multi Query KB");
        String leaveDocId = createDocument(kbId, "Leave Policy", "Annual leave is 10 days. Approval is required.");
        String securityDocId = createDocument(kbId, "Security Policy", "Security badges are required in the office.");
        publishDocument(leaveDocId);
        publishDocument(securityDocId);

        MvcResult first = mockMvc.perform(post("/api/kbs/{kbId}/chat", kbId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "question": "Tell me about security badges.",
                                  "profile": "auto"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ragProfile").value("naive"))
                .andExpect(jsonPath("$.sources[0].docId").value(securityDocId))
                .andReturn();
        String sessionId = objectMapper.readTree(first.getResponse().getContentAsString()).path("sessionId").asText();
        FakeEmbeddingConfig.reset();

        MvcResult followUp = mockMvc.perform(post("/api/kbs/{kbId}/chat", kbId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "question": "How many annual leave days are available?",
                                  "profile": "auto",
                                  "sessionId": "%s",
                                  "topK": 2
                                }
                                """.formatted(sessionId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ragProfile").value("modular"))
                .andExpect(jsonPath("$.sources[*].docId").value(org.hamcrest.Matchers.hasItem(leaveDocId)))
                .andExpect(jsonPath("$.sources[*].docId").value(org.hamcrest.Matchers.hasItem(securityDocId)))
                .andReturn();

        assertThat(FakeEmbeddingConfig.queryEmbeddingCalls()).isEqualTo(2);
        String traceId = objectMapper.readTree(followUp.getResponse().getContentAsString()).path("qaTraceId").asText();
        waitForTrace(traceId);

        Long tracedSources = jdbcClient.sql("""
                SELECT jsonb_array_length(retrieved_chunks)
                FROM qa_traces
                WHERE id = :traceId
                """)
                .param("traceId", traceId)
                .query(Long.class)
                .single();
        assertThat(tracedSources).isEqualTo(2);
    }

    @Test
    void acceptsExplicitNaiveProfile() throws Exception {
        String kbId = createKnowledgeBase("Naive Profile KB");
        String docId = createDocument(kbId, "Leave Policy", "Annual leave is 10 days. Approval is required.");
        publishDocument(docId);

        mockMvc.perform(post("/api/kbs/{kbId}/chat", kbId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "question": "How many annual leave days are available?",
                                  "profile": "NAIVE"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ragProfile").value("naive"))
                .andExpect(jsonPath("$.sources[0].docId").value(docId));
    }

    @Test
    void reranksRoughVectorCandidatesBeforeApplyingRequestedTopK() throws Exception {
        String kbId = createKnowledgeBase("Rerank KB");
        String genericDocId = createDocument(kbId, "Generic Leave Policy",
                "Leave policy says employees submit requests through the HR portal.");
        String annualDocId = createDocument(kbId, "Annual Leave Policy",
                "Annual leave is 10 days. Approval is required.");
        publishDocument(genericDocId);
        publishDocument(annualDocId);

        MvcResult result = mockMvc.perform(post("/api/kbs/{kbId}/chat", kbId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "question": "How many annual leave days are available?",
                                  "topK": 1
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.refused").value(false))
                .andExpect(jsonPath("$.sources", hasSize(1)))
                .andExpect(jsonPath("$.sources[0].docId").value(annualDocId))
                .andExpect(jsonPath("$.sources[0].snippet").value("Annual leave is 10 days. Approval is required."))
                .andReturn();

        String traceId = objectMapper.readTree(result.getResponse().getContentAsString()).path("qaTraceId").asText();
        waitForTrace(traceId);

        Long tracedSources = jdbcClient.sql("""
                SELECT jsonb_array_length(retrieved_chunks)
                FROM qa_traces
                WHERE id = :traceId
                """)
                .param("traceId", traceId)
                .query(Long.class)
                .single();
        assertThat(tracedSources).isEqualTo(1);
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
                .andExpect(content().string(org.hamcrest.Matchers.containsString("\"ragProfile\":\"naive\"")))
                .andReturn();
        assertThat(FakeEmbeddingConfig.streamingThreadName()).startsWith("chat-");

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
    void streamGenerationFailureFallsBackWithSourcesAndTrace() throws Exception {
        String kbId = createKnowledgeBase("Chat Failure KB");
        String docId = createDocument(kbId, "Leave Policy", "Annual leave is 10 days. Approval is required.");
        publishDocument(docId);
        FakeEmbeddingConfig.failChatGeneration();

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
                .andExpect(content().string(org.hamcrest.Matchers.containsString("\"docId\":\"" + docId + "\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("AI")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("event:done")))
                .andReturn();

        String traceId = extractTraceId(dispatched.getResponse().getContentAsString());
        waitForTrace(traceId);

        Long tracedFallback = jdbcClient.sql("""
                SELECT COUNT(*)
                FROM qa_traces
                WHERE id = :traceId
                  AND answer LIKE '%AI%'
                  AND jsonb_array_length(retrieved_chunks) = 1
                """)
                .param("traceId", traceId)
                .query(Long.class)
                .single();
        assertThat(tracedFallback).isEqualTo(1);
    }

    @Test
    void queryEmbeddingFailureFallsBackToEmptyContextRefusal() throws Exception {
        String kbId = createKnowledgeBase("Embedding Failure KB");
        String docId = createDocument(kbId, "Leave Policy", "Annual leave is 10 days. Approval is required.");
        publishDocument(docId);
        FakeEmbeddingConfig.failQueryEmbedding();

        MvcResult result = mockMvc.perform(post("/api/kbs/{kbId}/chat", kbId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "question": "How many annual leave days are available?"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.refused").value(true))
                .andExpect(jsonPath("$.sources", hasSize(0)))
                .andReturn();

        String traceId = objectMapper.readTree(result.getResponse().getContentAsString()).path("qaTraceId").asText();
        waitForTrace(traceId);

        Long tracedRefusal = jdbcClient.sql("""
                SELECT COUNT(*)
                FROM qa_traces
                WHERE id = :traceId
                  AND jsonb_array_length(retrieved_chunks) = 0
                """)
                .param("traceId", traceId)
                .query(Long.class)
                .single();
        assertThat(tracedRefusal).isEqualTo(1);
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
                .andExpect(jsonPath("$.llmMs").isNumber())
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
    void refusesChineseOutOfScopeQuestionWhenVectorSearchReturnsUnrelatedChunk() throws Exception {
        String kbId = createKnowledgeBase("Chinese Refusal KB");
        String docId = createDocument(kbId, "请假制度",
                "年假规则：员工连续工作满一年后可享受年假。审批流程：员工需要至少提前3个工作日在系统提交请假申请。病假材料：病假超过2天需要上传医院诊断证明或就诊记录。");
        publishDocument(docId);

        mockMvc.perform(post("/api/kbs/{kbId}/chat", kbId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "question": "公司股票代码是多少？"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.refused").value(true))
                .andExpect(jsonPath("$.sources", hasSize(0)));
    }

    @Test
    void refusesChineseQuestionWhenOnlyWeakGenericTermsOverlap() throws Exception {
        String kbId = createKnowledgeBase("Weak Evidence KB");
        String docId = createDocument(kbId, "请假制度",
                "请假制度说明：员工提交申请后，审批流程会记录在系统中。相关要求由部门负责人确认。");
        publishDocument(docId);

        mockMvc.perform(post("/api/kbs/{kbId}/chat", kbId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "question": "报销审批流程是什么？"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.refused").value(true))
                .andExpect(jsonPath("$.sources", hasSize(0)));
    }

    @Test
    void keepsChineseInScopeQuestionAnswerableAfterLexicalEvidenceGate() throws Exception {
        String kbId = createKnowledgeBase("Chinese In Scope KB");
        String docId = createDocument(kbId, "请假制度",
                "年假规则：员工连续工作满一年后可享受年假。审批流程：员工需要至少提前3个工作日在系统提交请假申请。病假材料：病假超过2天需要上传医院诊断证明或就诊记录。");
        publishDocument(docId);

        mockMvc.perform(post("/api/kbs/{kbId}/chat", kbId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "question": "年假有多少天？"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.refused").value(false))
                .andExpect(jsonPath("$.sources", hasSize(1)))
                .andExpect(jsonPath("$.sources[0].docId").value(docId));
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
                .andExpect(jsonPath("$.code").value(40001))
                .andExpect(jsonPath("$.message").value("Question is required."))
                .andExpect(jsonPath("$.timestamp").isNotEmpty());
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

    @Test
    void rejectsUnsupportedProfile() throws Exception {
        String kbId = createKnowledgeBase("Validation KB");

        mockMvc.perform(post("/api/kbs/{kbId}/chat", kbId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "question": "annual leave",
                                  "profile": "graph"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("profile must be auto, naive, or modular."));
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
        String docId = body.path("document").path("id").asText();
        waitForIngestReady(docId);
        return docId;
    }

    private void publishDocument(String docId) throws Exception {
        mockMvc.perform(post("/api/documents/{docId}/publish", docId))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.indexStatus").value("PENDING"));
        assertThat(indexOutboxService.processNextPendingEvent()).isTrue();
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

    private static String extractTraceId(String sseBody) {
        Matcher matcher = Pattern.compile("\"qaTraceId\":\"([^\"]+)\"").matcher(sseBody);
        assertThat(matcher.find()).isTrue();
        return matcher.group(1);
    }

    @TestConfiguration
    static class FakeEmbeddingConfig {

        private static final AtomicReference<String> STREAMING_THREAD_NAME = new AtomicReference<>();
        private static final AtomicBoolean FAIL_QUERY_EMBEDDING = new AtomicBoolean();
        private static final AtomicBoolean FAIL_CHAT_GENERATION = new AtomicBoolean();
        private static final java.util.concurrent.atomic.AtomicInteger QUERY_EMBEDDING_CALLS =
                new java.util.concurrent.atomic.AtomicInteger();
        private static final java.util.concurrent.atomic.AtomicInteger DOCUMENT_EMBEDDING_CALLS =
                new java.util.concurrent.atomic.AtomicInteger();

        @Bean
        DocumentEmbeddingGateway documentEmbeddingGateway() {
            return new DocumentEmbeddingGateway() {
                @Override
                public List<float[]> embed(List<String> texts) {
                    return embedDocuments(texts);
                }

                @Override
                public List<float[]> embedDocuments(List<String> texts) {
                    DOCUMENT_EMBEDDING_CALLS.incrementAndGet();
                    return embeddings(texts);
                }

                @Override
                public List<float[]> embedQuery(String text) {
                    QUERY_EMBEDDING_CALLS.incrementAndGet();
                    if (FAIL_QUERY_EMBEDDING.getAndSet(false)) {
                        throw new AiProviderException("AI embedding call failed.", new IllegalStateException("synthetic"));
                    }
                    return embeddings(List.of(text));
                }
            };
        }

        @Bean
        StreamingAnswerGenerator streamingAnswerGenerator() {
            return (question, sources, tokenConsumer) -> {
                STREAMING_THREAD_NAME.set(Thread.currentThread().getName());
                if (FAIL_CHAT_GENERATION.getAndSet(false)) {
                    throw new AiProviderException("AI chat call failed.", new IllegalStateException("synthetic"));
                }
                tokenConsumer.accept("streamed answer");
            };
        }

        @Bean
        AnswerGenerator answerGenerator() {
            return (question, sources) -> {
                if (FAIL_CHAT_GENERATION.getAndSet(false)) {
                    throw new AiProviderException("AI chat call failed.", new IllegalStateException("synthetic"));
                }
                return "Generated answer for: " + question;
            };
        }

        static void reset() {
            STREAMING_THREAD_NAME.set(null);
            FAIL_QUERY_EMBEDDING.set(false);
            FAIL_CHAT_GENERATION.set(false);
            QUERY_EMBEDDING_CALLS.set(0);
            DOCUMENT_EMBEDDING_CALLS.set(0);
        }

        static String streamingThreadName() {
            return STREAMING_THREAD_NAME.get();
        }

        static void failQueryEmbedding() {
            FAIL_QUERY_EMBEDDING.set(true);
        }

        static void failChatGeneration() {
            FAIL_CHAT_GENERATION.set(true);
        }

        static int queryEmbeddingCalls() {
            return QUERY_EMBEDDING_CALLS.get();
        }

        static int documentEmbeddingCalls() {
            return DOCUMENT_EMBEDDING_CALLS.get();
        }

        private static List<float[]> embeddings(List<String> texts) {
            return texts.stream()
                    .map(FakeEmbeddingConfig::embedding)
                    .toList();
        }

        private static float[] embedding(String text) {
            float[] embedding = new float[1024];
            String normalized = text.toLowerCase();
            if (normalized.contains("leave")) {
                embedding[0] = 1.0f;
            }
            if (normalized.contains("approval") || normalized.contains("approve")) {
                embedding[0] = 1.0f;
            }
            if (normalized.contains("security")) {
                embedding[1] = 1.0f;
            }
            if (embedding[0] == 0.0f && embedding[1] == 0.0f) {
                embedding[2] = 1.0f;
            }
            return embedding;
        }
    }
}

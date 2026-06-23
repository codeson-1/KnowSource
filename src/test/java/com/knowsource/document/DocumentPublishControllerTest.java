package com.knowsource.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;

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
class DocumentPublishControllerTest {

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
    void publishCreatesOutboxEventAndIndexesDocumentVectors() throws Exception {
        String kbId = createKnowledgeBase("HR KB");
        String docId = createDocument(kbId, "Leave Policy", "年假规则。审批流程。病假规则。");

        Long draftVectors = jdbcClient.sql("SELECT COUNT(*) FROM vector_store WHERE doc_id = :docId")
                .param("docId", docId)
                .query(Long.class)
                .single();
        assertThat(draftVectors).isZero();

        mockMvc.perform(post("/api/documents/{docId}/publish", docId))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.docId").value(docId))
                .andExpect(jsonPath("$.kbId").value(kbId))
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.indexStatus").value("SYNCED"))
                .andExpect(jsonPath("$.eventId").isNotEmpty())
                .andExpect(jsonPath("$.message").value("Document published and indexed."));

        String documentStatus = jdbcClient.sql("SELECT status FROM documents WHERE id = :docId")
                .param("docId", docId)
                .query(String.class)
                .single();
        String indexStatus = jdbcClient.sql("SELECT index_status FROM documents WHERE id = :docId")
                .param("docId", docId)
                .query(String.class)
                .single();
        Long syncedAtCount = jdbcClient.sql("""
                SELECT COUNT(*)
                FROM documents
                WHERE id = :docId AND published_at IS NOT NULL AND vectors_synced_at IS NOT NULL
                """)
                .param("docId", docId)
                .query(Long.class)
                .single();
        Long doneEvents = jdbcClient.sql("""
                SELECT COUNT(*)
                FROM document_publish_events
                WHERE doc_id = :docId AND doc_version = 1 AND event_type = 'PUBLISH' AND status = 'DONE'
                """)
                .param("docId", docId)
                .query(Long.class)
                .single();
        Long childChunks = jdbcClient.sql("SELECT COUNT(*) FROM chunk_children WHERE doc_id = :docId AND doc_version = 1")
                .param("docId", docId)
                .query(Long.class)
                .single();
        Long vectorRows = jdbcClient.sql("""
                SELECT COUNT(*)
                FROM vector_store
                WHERE kb_id = :kbId AND doc_id = :docId AND doc_version = 1 AND status = 'published'
                """)
                .param("kbId", kbId)
                .param("docId", docId)
                .query(Long.class)
                .single();

        assertThat(documentStatus).isEqualTo("PUBLISHED");
        assertThat(indexStatus).isEqualTo("SYNCED");
        assertThat(syncedAtCount).isEqualTo(1);
        assertThat(doneEvents).isEqualTo(1);
        assertThat(vectorRows).isEqualTo(childChunks);
    }

    @Test
    void publishRejectsDocumentWithoutReadyIngestTask() throws Exception {
        String kbId = createKnowledgeBase("Broken KB");
        String docId = "doc-without-ready-task";
        long userId = jdbcClient.sql("SELECT id FROM users WHERE username = 'demo'")
                .query(Long.class)
                .single();

        jdbcClient.sql("""
                INSERT INTO documents (id, kb_id, title, status, index_status, oss_key, version, file_type, created_by)
                VALUES (:docId, :kbId, 'Broken Doc', 'DRAFT', 'NONE', 'inline://broken', 1, 'TEXT', :userId)
                """)
                .param("docId", docId)
                .param("kbId", kbId)
                .param("userId", userId)
                .update();
        jdbcClient.sql("INSERT INTO ingest_tasks (id, doc_id, status) VALUES ('task-broken', :docId, 'FAILED')")
                .param("docId", docId)
                .update();

        mockMvc.perform(post("/api/documents/{docId}/publish", docId))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Document ingest task is not READY."));
    }

    @Test
    void processingRecoversStaleSyncingEventBeforeClaimingNextEvent() throws Exception {
        String kbId = createKnowledgeBase("Recovery KB");
        String docId = createDocument(kbId, "Leave Policy", "年假规则。审批流程。");
        long userId = jdbcClient.sql("SELECT id FROM users WHERE username = 'demo'")
                .query(Long.class)
                .single();

        jdbcClient.sql("""
                UPDATE documents
                SET status = 'PUBLISHED', index_status = 'SYNCING', published_at = NOW()
                WHERE id = :docId
                """)
                .param("docId", docId)
                .update();
        jdbcClient.sql("""
                INSERT INTO document_publish_events
                    (id, doc_id, kb_id, doc_version, event_type, status, attempt_count, locked_at, locked_by, created_at, updated_at)
                VALUES
                    ('stale-event', :docId, :kbId, 1, 'PUBLISH', 'SYNCING', 0,
                     NOW() - INTERVAL '10 minutes', 'dead-worker', NOW() - INTERVAL '10 minutes', NOW() - INTERVAL '10 minutes')
                """)
                .param("docId", docId)
                .param("kbId", kbId)
                .update();

        mockMvc.perform(post("/api/documents/{docId}/publish", docId))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.indexStatus").value("SYNCED"));

        Long staleEvents = jdbcClient.sql("""
                SELECT COUNT(*)
                FROM document_publish_events
                WHERE id = 'stale-event'
                  AND status = 'DONE'
                  AND attempt_count = 1
                  AND processed_at IS NOT NULL
                """)
                .query(Long.class)
                .single();
        Long vectorRows = jdbcClient.sql("""
                SELECT COUNT(*)
                FROM vector_store
                WHERE kb_id = :kbId AND doc_id = :docId AND doc_version = 1 AND status = 'published'
                """)
                .param("kbId", kbId)
                .param("docId", docId)
                .query(Long.class)
                .single();

        assertThat(userId).isPositive();
        assertThat(staleEvents).isEqualTo(1);
        assertThat(vectorRows).isPositive();
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

    @TestConfiguration
    static class FakeEmbeddingConfig {

        @Bean
        DocumentEmbeddingGateway documentEmbeddingGateway() {
            return texts -> texts.stream()
                    .map(FakeEmbeddingConfig::embedding)
                    .toList();
        }

        private static float[] embedding(String text) {
            List<Float> values = new ArrayList<>(1024);
            float seed = Math.max(1, text.length());
            for (int i = 0; i < 1024; i++) {
                values.add((seed + i) / 10_000.0f);
            }

            float[] embedding = new float[values.size()];
            for (int i = 0; i < values.size(); i++) {
                embedding[i] = values.get(i);
            }
            return embedding;
        }
    }
}

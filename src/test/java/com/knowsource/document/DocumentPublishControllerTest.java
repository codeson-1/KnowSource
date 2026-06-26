package com.knowsource.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

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

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("db")
@WithMockUser(username = "demo", roles = "ADMIN")
class DocumentPublishControllerTest {

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
    void publishCreatesPendingOutboxEventWithoutIndexingSynchronously() throws Exception {
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
                .andExpect(jsonPath("$.indexStatus").value("PENDING"))
                .andExpect(jsonPath("$.eventId").isNotEmpty())
                .andExpect(jsonPath("$.message").value("Document published; indexing is pending."));

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
                WHERE id = :docId AND published_at IS NOT NULL AND vectors_synced_at IS NULL
                """)
                .param("docId", docId)
                .query(Long.class)
                .single();
        Long pendingEvents = jdbcClient.sql("""
                SELECT COUNT(*)
                FROM document_publish_events
                WHERE doc_id = :docId AND doc_version = 1 AND event_type = 'PUBLISH' AND status = 'PENDING'
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
        assertThat(indexStatus).isEqualTo("PENDING");
        assertThat(syncedAtCount).isEqualTo(1);
        assertThat(pendingEvents).isEqualTo(1);
        assertThat(childChunks).isPositive();
        assertThat(vectorRows).isZero();
    }

    @Test
    void outboxConsumerIndexesPendingPublishEvent() throws Exception {
        String kbId = createKnowledgeBase("Outbox KB");
        String docId = createDocument(kbId, "Leave Policy", "Annual leave policy. Approval process.");

        mockMvc.perform(post("/api/documents/{docId}/publish", docId))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.indexStatus").value("PENDING"));

        assertThat(indexOutboxService.processNextPendingEvent()).isTrue();

        String indexStatus = jdbcClient.sql("SELECT index_status FROM documents WHERE id = :docId")
                .param("docId", docId)
                .query(String.class)
                .single();
        Long syncedAtCount = jdbcClient.sql("""
                SELECT COUNT(*)
                FROM documents
                WHERE id = :docId AND vectors_synced_at IS NOT NULL
                """)
                .param("docId", docId)
                .query(Long.class)
                .single();
        Long processedEvents = jdbcClient.sql("""
                SELECT COUNT(*)
                FROM document_publish_events
                WHERE doc_id = :docId AND doc_version = 1 AND event_type = 'PUBLISH' AND status = 'PROCESSED'
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

        assertThat(indexStatus).isEqualTo("SYNCED");
        assertThat(syncedAtCount).isEqualTo(1);
        assertThat(processedEvents).isEqualTo(1);
        assertThat(vectorRows).isEqualTo(childChunks);
    }

    @Test
    void archivesPublishedDocumentAndRemovesVectors() throws Exception {
        String kbId = createKnowledgeBase("Archive KB");
        String docId = createDocument(kbId, "Archive Policy", "Archive policy should be indexed then removed.");

        mockMvc.perform(post("/api/documents/{docId}/publish", docId))
                .andExpect(status().isAccepted());
        assertThat(indexOutboxService.processNextPendingEvent()).isTrue();

        Long indexedRows = jdbcClient.sql("SELECT COUNT(*) FROM vector_store WHERE doc_id = :docId")
                .param("docId", docId)
                .query(Long.class)
                .single();
        assertThat(indexedRows).isPositive();

        mockMvc.perform(post("/api/documents/{docId}/archive", docId))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.docId").value(docId))
                .andExpect(jsonPath("$.indexStatus").value("NONE"))
                .andExpect(jsonPath("$.message").value("Document archived; vectors were removed."));

        String documentStatus = jdbcClient.sql("SELECT status FROM documents WHERE id = :docId")
                .param("docId", docId)
                .query(String.class)
                .single();
        Long vectorRows = jdbcClient.sql("SELECT COUNT(*) FROM vector_store WHERE doc_id = :docId")
                .param("docId", docId)
                .query(Long.class)
                .single();

        assertThat(documentStatus).isEqualTo("ARCHIVED");
        assertThat(vectorRows).isZero();
    }

    @Test
    void replacingDocumentIncrementsVersionAndNewPublishIndexesOnlyCurrentVersion() throws Exception {
        String kbId = createKnowledgeBase("Replace KB");
        String docId = createDocument(kbId, "Old Policy", "Old annual leave rules.");

        mockMvc.perform(post("/api/documents/{docId}/publish", docId))
                .andExpect(status().isAccepted());
        assertThat(indexOutboxService.processNextPendingEvent()).isTrue();

        mockMvc.perform(put("/api/documents/{docId}", docId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "title": "New Policy",
                                  "content": "New annual leave rules with updated approval."
                                }
                                """))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.document.id").value(docId))
                .andExpect(jsonPath("$.document.version").value(2))
                .andExpect(jsonPath("$.document.status").value("DRAFT"))
                .andExpect(jsonPath("$.document.indexStatus").value("NONE"));
        waitForIngestReady(docId);

        Long oldVectorRows = jdbcClient.sql("SELECT COUNT(*) FROM vector_store WHERE doc_id = :docId")
                .param("docId", docId)
                .query(Long.class)
                .single();
        assertThat(oldVectorRows).isZero();

        mockMvc.perform(post("/api/documents/{docId}/publish", docId))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.version").value(2));
        assertThat(indexOutboxService.processNextPendingEvent()).isTrue();

        Long currentVersionVectors = jdbcClient.sql("""
                SELECT COUNT(*)
                FROM vector_store
                WHERE doc_id = :docId AND doc_version = 2
                """)
                .param("docId", docId)
                .query(Long.class)
                .single();
        Long previousVersionVectors = jdbcClient.sql("""
                SELECT COUNT(*)
                FROM vector_store
                WHERE doc_id = :docId AND doc_version = 1
                """)
                .param("docId", docId)
                .query(Long.class)
                .single();

        assertThat(currentVersionVectors).isPositive();
        assertThat(previousVersionVectors).isZero();
    }

    @Test
    void outboxConsumerWritesChunkMetadataIntoVectorRows() throws Exception {
        String kbId = createKnowledgeBase("Metadata KB");
        String docId = createDocument(kbId, "Policy Table", "Leave table Type Days Annual 10");
        jdbcClient.sql("""
                UPDATE chunk_children
                SET page_number = 3, chunk_type = 'TABLE'
                WHERE doc_id = :docId AND chunk_index = 0
                """)
                .param("docId", docId)
                .update();

        mockMvc.perform(post("/api/documents/{docId}/publish", docId))
                .andExpect(status().isAccepted());

        assertThat(indexOutboxService.processNextPendingEvent()).isTrue();

        Long metadataRows = jdbcClient.sql("""
                SELECT COUNT(*)
                FROM vector_store
                WHERE doc_id = :docId
                  AND metadata ->> 'chunkType' = 'TABLE'
                  AND (metadata ->> 'pageNumber')::int = 3
                  AND metadata -> 'ingestMetadata' ->> 'blockIndex' IS NOT NULL
                  AND metadata -> 'ingestMetadata' ->> 'startOffset' IS NOT NULL
                """)
                .param("docId", docId)
                .query(Long.class)
                .single();

        assertThat(metadataRows).isEqualTo(1);
    }

    @Test
    void outboxConsumerEmbedsDocumentChunksInConfiguredBatches() throws Exception {
        String kbId = createKnowledgeBase("Batch KB");
        String docId = createDocument(kbId, "Long Policy", "A".repeat(1_800));

        mockMvc.perform(post("/api/documents/{docId}/publish", docId))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.indexStatus").value("PENDING"));

        assertThat(indexOutboxService.processNextPendingEvent()).isTrue();

        Long childChunks = jdbcClient.sql("SELECT COUNT(*) FROM chunk_children WHERE doc_id = :docId AND doc_version = 1")
                .param("docId", docId)
                .query(Long.class)
                .single();
        Long vectorRows = jdbcClient.sql("SELECT COUNT(*) FROM vector_store WHERE doc_id = :docId AND doc_version = 1")
                .param("docId", docId)
                .query(Long.class)
                .single();

        assertThat(childChunks).isEqualTo(5);
        assertThat(vectorRows).isEqualTo(childChunks);
        assertThat(FakeEmbeddingConfig.batchSizes()).containsExactly(2, 2, 1);
    }

    @Test
    void outboxFailureUsesConfiguredExponentialRetryBackoff() throws Exception {
        String kbId = createKnowledgeBase("Retry KB");
        String docId = createDocument(kbId, "Leave Policy", "Annual leave policy. Approval process.");
        FakeEmbeddingConfig.failNextEmbeddingRequests(2);

        mockMvc.perform(post("/api/documents/{docId}/publish", docId))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.indexStatus").value("PENDING"));

        assertThat(indexOutboxService.processNextPendingEvent()).isTrue();

        Long firstFailure = jdbcClient.sql("""
                SELECT COUNT(*)
                FROM document_publish_events
                WHERE doc_id = :docId
                  AND status = 'FAILED'
                  AND attempt_count = 1
                  AND next_retry_at > NOW() + INTERVAL '1 second'
                  AND next_retry_at <= NOW() + INTERVAL '3 seconds'
                """)
                .param("docId", docId)
                .query(Long.class)
                .single();
        assertThat(firstFailure).isEqualTo(1);

        jdbcClient.sql("""
                UPDATE document_publish_events
                SET next_retry_at = NOW()
                WHERE doc_id = :docId
                """)
                .param("docId", docId)
                .update();

        assertThat(indexOutboxService.processNextPendingEvent()).isTrue();

        Long secondFailure = jdbcClient.sql("""
                SELECT COUNT(*)
                FROM document_publish_events
                WHERE doc_id = :docId
                  AND status = 'FAILED'
                  AND attempt_count = 2
                  AND next_retry_at > NOW() + INTERVAL '3 seconds'
                  AND next_retry_at <= NOW() + INTERVAL '5 seconds'
                """)
                .param("docId", docId)
                .query(Long.class)
                .single();
        String indexStatus = jdbcClient.sql("SELECT index_status FROM documents WHERE id = :docId")
                .param("docId", docId)
                .query(String.class)
                .single();

        assertThat(secondFailure).isEqualTo(1);
        assertThat(indexStatus).isEqualTo("FAILED");
    }

    @Test
    void requeuesFailedIndexEventAndAllowsProcessingAgain() throws Exception {
        String kbId = createKnowledgeBase("Manual Requeue KB");
        String docId = createDocument(kbId, "Leave Policy", "Annual leave policy. Approval process.");
        FakeEmbeddingConfig.failNextEmbeddingRequests(1);

        MvcResult publishResult = mockMvc.perform(post("/api/documents/{docId}/publish", docId))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.indexStatus").value("PENDING"))
                .andReturn();
        String eventId = objectMapper.readTree(publishResult.getResponse().getContentAsString())
                .path("eventId")
                .asText();

        assertThat(indexOutboxService.processNextPendingEvent()).isTrue();

        mockMvc.perform(post("/api/documents/{docId}/index-events/{eventId}/requeue", docId, eventId))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.docId").value(docId))
                .andExpect(jsonPath("$.eventId").value(eventId))
                .andExpect(jsonPath("$.indexStatus").value("PENDING"))
                .andExpect(jsonPath("$.message").value("Index event requeued; indexing is pending."));

        String eventStatus = jdbcClient.sql("SELECT status FROM document_publish_events WHERE id = :eventId")
                .param("eventId", eventId)
                .query(String.class)
                .single();
        Integer attemptCount = jdbcClient.sql("SELECT attempt_count FROM document_publish_events WHERE id = :eventId")
                .param("eventId", eventId)
                .query(Integer.class)
                .single();
        String documentIndexStatus = jdbcClient.sql("SELECT index_status FROM documents WHERE id = :docId")
                .param("docId", docId)
                .query(String.class)
                .single();
        assertThat(eventStatus).isEqualTo("PENDING");
        assertThat(attemptCount).isZero();
        assertThat(documentIndexStatus).isEqualTo("PENDING");

        assertThat(indexOutboxService.processNextPendingEvent()).isTrue();

        Long processedEvents = jdbcClient.sql("""
                SELECT COUNT(*)
                FROM document_publish_events
                WHERE id = :eventId AND status = 'PROCESSED' AND error_message IS NULL
                """)
                .param("eventId", eventId)
                .query(Long.class)
                .single();
        Long vectorRows = jdbcClient.sql("SELECT COUNT(*) FROM vector_store WHERE doc_id = :docId AND doc_version = 1")
                .param("docId", docId)
                .query(Long.class)
                .single();

        assertThat(processedEvents).isEqualTo(1);
        assertThat(vectorRows).isPositive();
    }

    @Test
    void retriesLatestFailedIndexEventWithoutClientRememberingEventId() throws Exception {
        String kbId = createKnowledgeBase("Direct Retry KB");
        String docId = createDocument(kbId, "Leave Policy", "Annual leave policy. Approval process.");
        FakeEmbeddingConfig.failNextEmbeddingRequests(1);

        mockMvc.perform(post("/api/documents/{docId}/publish", docId))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.indexStatus").value("PENDING"));

        assertThat(indexOutboxService.processNextPendingEvent()).isTrue();

        MvcResult listResult = mockMvc.perform(get("/api/kbs/{kbId}/documents", kbId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(docId))
                .andExpect(jsonPath("$[0].indexStatus").value("FAILED"))
                .andExpect(jsonPath("$[0].latestFailedIndexEventId").isNotEmpty())
                .andReturn();

        String failedEventId = objectMapper.readTree(listResult.getResponse().getContentAsString())
                .path(0)
                .path("latestFailedIndexEventId")
                .asText();

        mockMvc.perform(post("/api/documents/{docId}/index/retry", docId))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.docId").value(docId))
                .andExpect(jsonPath("$.eventId").value(failedEventId))
                .andExpect(jsonPath("$.indexStatus").value("PENDING"))
                .andExpect(jsonPath("$.message").value("Latest failed index event requeued; indexing is pending."));

        String eventStatus = jdbcClient.sql("SELECT status FROM document_publish_events WHERE id = :eventId")
                .param("eventId", failedEventId)
                .query(String.class)
                .single();
        assertThat(eventStatus).isEqualTo("PENDING");

        assertThat(indexOutboxService.processNextPendingEvent()).isTrue();

        Long vectorRows = jdbcClient.sql("SELECT COUNT(*) FROM vector_store WHERE doc_id = :docId AND doc_version = 1")
                .param("docId", docId)
                .query(Long.class)
                .single();
        assertThat(vectorRows).isPositive();
    }

    @Test
    void rejectsDirectIndexRetryWhenDocumentHasNoFailedIndexEvent() throws Exception {
        String kbId = createKnowledgeBase("No Failed Retry KB");
        String docId = createDocument(kbId, "Leave Policy", "Annual leave policy. Approval process.");

        mockMvc.perform(post("/api/documents/{docId}/index/retry", docId))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Document has no FAILED index event to retry."));
    }

    @Test
    void rejectsRequeueForPendingIndexEvent() throws Exception {
        String kbId = createKnowledgeBase("Pending Requeue KB");
        String docId = createDocument(kbId, "Leave Policy", "Annual leave policy. Approval process.");

        MvcResult publishResult = mockMvc.perform(post("/api/documents/{docId}/publish", docId))
                .andExpect(status().isAccepted())
                .andReturn();
        String eventId = objectMapper.readTree(publishResult.getResponse().getContentAsString())
                .path("eventId")
                .asText();

        mockMvc.perform(post("/api/documents/{docId}/index-events/{eventId}/requeue", docId, eventId))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Only FAILED index events can be requeued."));
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
                     NOW() - INTERVAL '31 minutes', 'dead-worker', NOW() - INTERVAL '31 minutes', NOW() - INTERVAL '31 minutes')
                """)
                .param("docId", docId)
                .param("kbId", kbId)
                .update();

        mockMvc.perform(post("/api/documents/{docId}/publish", docId))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.indexStatus").value("PENDING"));

        assertThat(indexOutboxService.processNextPendingEvent()).isTrue();

        Long staleEvents = jdbcClient.sql("""
                SELECT COUNT(*)
                FROM document_publish_events
                WHERE id = 'stale-event'
                  AND status = 'PROCESSED'
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
        String docId = body.path("document").path("id").asText();
        waitForIngestReady(docId);
        return docId;
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
        org.assertj.core.api.Assertions.fail("Timed out waiting for ingest task READY for " + docId);
    }

    @TestConfiguration
    static class FakeEmbeddingConfig {

        private static final List<Integer> BATCH_SIZES = new CopyOnWriteArrayList<>();
        private static final AtomicInteger FAILURES_REMAINING = new AtomicInteger();

        @Bean
        DocumentEmbeddingGateway documentEmbeddingGateway() {
            return new DocumentEmbeddingGateway() {
                @Override
                public List<float[]> embed(List<String> texts) {
                    return embedDocuments(texts);
                }

                @Override
                public List<float[]> embedDocuments(List<String> texts) {
                    BATCH_SIZES.add(texts.size());
                    if (FAILURES_REMAINING.getAndUpdate(value -> Math.max(0, value - 1)) > 0) {
                        throw new IllegalStateException("Synthetic embedding failure.");
                    }
                    return texts.stream()
                            .map(FakeEmbeddingConfig::embedding)
                            .toList();
                }
            };
        }

        static void reset() {
            BATCH_SIZES.clear();
            FAILURES_REMAINING.set(0);
        }

        static void failNextEmbeddingRequests(int count) {
            FAILURES_REMAINING.set(count);
        }

        static List<Integer> batchSizes() {
            return List.copyOf(BATCH_SIZES);
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

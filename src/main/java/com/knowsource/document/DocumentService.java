package com.knowsource.document;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import com.knowsource.index.DocumentIndexOutboxService;
import com.knowsource.user.DemoUserService;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

@Service
public class DocumentService {

    private static final int MAX_TITLE_LENGTH = 256;

    private final JdbcClient jdbcClient;
    private final DemoUserService demoUserService;
    private final SimpleTextChunker textChunker;
    private final TransactionTemplate transactionTemplate;
    private final DocumentIndexOutboxService indexOutboxService;

    public DocumentService(
            JdbcClient jdbcClient,
            DemoUserService demoUserService,
            SimpleTextChunker textChunker,
            TransactionTemplate transactionTemplate,
            DocumentIndexOutboxService indexOutboxService) {
        this.jdbcClient = jdbcClient;
        this.demoUserService = demoUserService;
        this.textChunker = textChunker;
        this.transactionTemplate = transactionTemplate;
        this.indexOutboxService = indexOutboxService;
    }

    public DocumentIngestResponse ingest(String kbId, CreateDocumentRequest request) {
        long userId = demoUserService.currentUserId();
        requireKbMember(kbId, userId);

        String title = normalizeTitle(request.title());
        String content = normalizeContent(request.content());
        String docId = UUID.randomUUID().toString();
        String ingestTaskId = UUID.randomUUID().toString();

        DocumentResponse document = createDocumentAndTask(kbId, userId, title, docId, ingestTaskId);

        try {
            List<SimpleTextChunker.ParentChunk> parentChunks = textChunker.split(content);
            int childChunkCount = persistChunksAndMarkReady(docId, document.version(), ingestTaskId, parentChunks);

            return new DocumentIngestResponse(document, ingestTaskId, "READY", parentChunks.size(), childChunkCount);
        } catch (RuntimeException ex) {
            markIngestFailed(ingestTaskId, ex);
            throw ex;
        }
    }

    public List<DocumentResponse> listByKnowledgeBase(String kbId) {
        long userId = demoUserService.currentUserId();
        requireKbMember(kbId, userId);

        return jdbcClient.sql("""
                SELECT id, kb_id, title, status, index_status, oss_key, version, file_type,
                       created_by, published_at, vectors_synced_at, created_at
                FROM documents
                WHERE kb_id = :kbId
                ORDER BY created_at DESC, id DESC
                """)
                .param("kbId", kbId)
                .query(DocumentService::mapDocument)
                .list();
    }

    public DocumentResponse getDocument(String docId) {
        long userId = demoUserService.currentUserId();

        return jdbcClient.sql("""
                SELECT d.id, d.kb_id, d.title, d.status, d.index_status, d.oss_key, d.version, d.file_type,
                       d.created_by, d.published_at, d.vectors_synced_at, d.created_at
                FROM documents d
                JOIN kb_members member ON member.kb_id = d.kb_id
                WHERE d.id = :docId AND member.user_id = :userId
                """)
                .param("docId", docId)
                .param("userId", userId)
                .query(DocumentService::mapDocument)
                .optional()
                .orElseThrow(() -> new ResourceNotFoundException("Document not found."));
    }

    public List<DocumentChunkResponse> listChunks(String docId) {
        DocumentResponse document = getDocument(docId);

        return jdbcClient.sql("""
                SELECT id, doc_id, doc_version, parent_chunk_id, content, chunk_index, page_number, chunk_type
                FROM chunk_children
                WHERE doc_id = :docId AND doc_version = :docVersion
                ORDER BY chunk_index ASC
                """)
                .param("docId", document.id())
                .param("docVersion", document.version())
                .query(DocumentService::mapChunk)
                .list();
    }

    public DocumentPublishResponse publish(String docId) {
        long userId = demoUserService.currentUserId();
        DocumentPublishResponse response = createPublishEvent(docId, userId);
        indexOutboxService.processNextPendingEvent();
        DocumentResponse document = getDocument(docId);

        return new DocumentPublishResponse(
                response.docId(),
                response.kbId(),
                response.version(),
                document.indexStatus(),
                response.eventId(),
                publishMessage(document.indexStatus()));
    }

    private DocumentPublishResponse createPublishEvent(String docId, long userId) {
        return transactionTemplate.execute(status -> {
            DocumentResponse document = getDocumentForMember(docId, userId);
            requireLatestIngestReady(docId);

            String eventId = UUID.randomUUID().toString();

            jdbcClient.sql("""
                    UPDATE documents
                    SET status = 'PUBLISHED',
                        index_status = 'PENDING',
                        published_at = COALESCE(published_at, NOW()),
                        vectors_synced_at = NULL
                    WHERE id = :docId
                    """)
                    .param("docId", docId)
                    .update();

            jdbcClient.sql("""
                    INSERT INTO document_publish_events
                        (id, doc_id, kb_id, doc_version, event_type, status)
                    VALUES
                        (:id, :docId, :kbId, :docVersion, 'PUBLISH', 'PENDING')
                    """)
                    .param("id", eventId)
                    .param("docId", docId)
                    .param("kbId", document.kbId())
                    .param("docVersion", document.version())
                    .update();

            return new DocumentPublishResponse(
                    document.id(),
                    document.kbId(),
                    document.version(),
                    "PENDING",
                    eventId,
                    "Document published; indexing is pending.");
        });
    }

    private DocumentResponse createDocumentAndTask(String kbId, long userId, String title, String docId, String ingestTaskId) {
        return transactionTemplate.execute(status -> {
            DocumentResponse document = jdbcClient.sql("""
                    INSERT INTO documents (id, kb_id, title, status, index_status, oss_key, version, file_type, created_by)
                    VALUES (:id, :kbId, :title, 'DRAFT', 'NONE', :ossKey, 1, 'TEXT', :createdBy)
                    RETURNING id, kb_id, title, status, index_status, oss_key, version, file_type,
                              created_by, published_at, vectors_synced_at, created_at
                    """)
                    .param("id", docId)
                    .param("kbId", kbId)
                    .param("title", title)
                    .param("ossKey", "inline://" + docId)
                    .param("createdBy", userId)
                    .query(DocumentService::mapDocument)
                    .single();

            jdbcClient.sql("""
                    INSERT INTO ingest_tasks (id, doc_id, status, started_at)
                    VALUES (:id, :docId, 'PARSING', NOW())
                    """)
                    .param("id", ingestTaskId)
                    .param("docId", docId)
                    .update();

            return document;
        });
    }

    private int persistChunksAndMarkReady(
            String docId,
            int docVersion,
            String ingestTaskId,
            List<SimpleTextChunker.ParentChunk> parentChunks) {
        return transactionTemplate.execute(status -> {
            int childChunkCount = persistChunks(docId, docVersion, parentChunks);
            jdbcClient.sql("""
                    UPDATE ingest_tasks
                    SET status = 'READY', finished_at = NOW(), error_message = NULL
                    WHERE id = :id
                    """)
                    .param("id", ingestTaskId)
                    .update();
            return childChunkCount;
        });
    }

    private void markIngestFailed(String ingestTaskId, RuntimeException ex) {
        transactionTemplate.executeWithoutResult(status -> jdbcClient.sql("""
                UPDATE ingest_tasks
                SET status = 'FAILED', finished_at = NOW(), error_message = :errorMessage
                WHERE id = :id
                """)
                .param("id", ingestTaskId)
                .param("errorMessage", failureMessage(ex))
                .update());
    }

    private String failureMessage(RuntimeException ex) {
        if (StringUtils.hasText(ex.getMessage())) {
            return ex.getMessage();
        }
        return ex.getClass().getSimpleName();
    }

    private int persistChunks(String docId, int docVersion, List<SimpleTextChunker.ParentChunk> parentChunks) {
        int childChunkIndex = 0;

        for (SimpleTextChunker.ParentChunk parentChunk : parentChunks) {
            String parentChunkId = docId + "-p-" + parentChunk.parentIndex();
            jdbcClient.sql("""
                    INSERT INTO chunk_parents (id, doc_id, doc_version, content)
                    VALUES (:id, :docId, :docVersion, :content)
                    """)
                    .param("id", parentChunkId)
                    .param("docId", docId)
                    .param("docVersion", docVersion)
                    .param("content", parentChunk.content())
                    .update();

            for (String childContent : parentChunk.children()) {
                jdbcClient.sql("""
                        INSERT INTO chunk_children
                            (id, doc_id, doc_version, parent_chunk_id, content, chunk_index, chunk_type)
                        VALUES
                            (:id, :docId, :docVersion, :parentChunkId, :content, :chunkIndex, 'TEXT')
                        """)
                        .param("id", docId + "-c-" + childChunkIndex)
                        .param("docId", docId)
                        .param("docVersion", docVersion)
                        .param("parentChunkId", parentChunkId)
                        .param("content", childContent)
                        .param("chunkIndex", childChunkIndex)
                        .update();
                childChunkIndex++;
            }
        }

        return childChunkIndex;
    }

    private void requireKbMember(String kbId, long userId) {
        Long membershipCount = jdbcClient.sql("""
                SELECT COUNT(*)
                FROM kb_members
                WHERE kb_id = :kbId AND user_id = :userId
                """)
                .param("kbId", kbId)
                .param("userId", userId)
                .query(Long.class)
                .single();

        if (membershipCount == 0) {
            throw new ResourceNotFoundException("Knowledge base not found.");
        }
    }

    private DocumentResponse getDocumentForMember(String docId, long userId) {
        return jdbcClient.sql("""
                SELECT d.id, d.kb_id, d.title, d.status, d.index_status, d.oss_key, d.version, d.file_type,
                       d.created_by, d.published_at, d.vectors_synced_at, d.created_at
                FROM documents d
                JOIN kb_members member ON member.kb_id = d.kb_id
                WHERE d.id = :docId AND member.user_id = :userId
                """)
                .param("docId", docId)
                .param("userId", userId)
                .query(DocumentService::mapDocument)
                .optional()
                .orElseThrow(() -> new ResourceNotFoundException("Document not found."));
    }

    private void requireLatestIngestReady(String docId) {
        String latestStatus = jdbcClient.sql("""
                SELECT status
                FROM ingest_tasks
                WHERE doc_id = :docId
                ORDER BY created_at DESC, id DESC
                LIMIT 1
                """)
                .param("docId", docId)
                .query(String.class)
                .optional()
                .orElseThrow(() -> new IllegalArgumentException("Document has no ingest task."));

        if (!"READY".equals(latestStatus)) {
            throw new IllegalArgumentException("Document ingest task is not READY.");
        }
    }

    private static String publishMessage(String indexStatus) {
        if ("SYNCED".equals(indexStatus)) {
            return "Document published and indexed.";
        }
        if ("FAILED".equals(indexStatus)) {
            return "Document published but indexing failed.";
        }
        return "Document published; indexing is in progress.";
    }

    private String normalizeTitle(String title) {
        if (!StringUtils.hasText(title)) {
            throw new IllegalArgumentException("Document title is required.");
        }

        String normalized = title.trim();
        if (normalized.length() > MAX_TITLE_LENGTH) {
            throw new IllegalArgumentException("Document title must be at most 256 characters.");
        }
        return normalized;
    }

    private String normalizeContent(String content) {
        if (!StringUtils.hasText(content)) {
            throw new IllegalArgumentException("Document content is required.");
        }
        return content.trim();
    }

    private static DocumentResponse mapDocument(ResultSet rs, int rowNum) throws SQLException {
        return new DocumentResponse(
                rs.getString("id"),
                rs.getString("kb_id"),
                rs.getString("title"),
                rs.getString("status"),
                rs.getString("index_status"),
                rs.getString("oss_key"),
                rs.getInt("version"),
                rs.getString("file_type"),
                rs.getLong("created_by"),
                toLocalDateTime(rs.getTimestamp("published_at")),
                toLocalDateTime(rs.getTimestamp("vectors_synced_at")),
                rs.getTimestamp("created_at").toLocalDateTime());
    }

    private static DocumentChunkResponse mapChunk(ResultSet rs, int rowNum) throws SQLException {
        return new DocumentChunkResponse(
                rs.getString("id"),
                rs.getString("doc_id"),
                rs.getInt("doc_version"),
                rs.getString("parent_chunk_id"),
                rs.getString("content"),
                rs.getInt("chunk_index"),
                (Integer) rs.getObject("page_number"),
                rs.getString("chunk_type"));
    }

    private static LocalDateTime toLocalDateTime(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toLocalDateTime();
    }
}

package com.knowsource.document;

import java.io.IOException;
import java.io.InputStream;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Locale;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.knowsource.index.DocumentIndexOutboxService;
import com.knowsource.index.VectorIndexService;
import com.knowsource.security.CurrentUser;
import com.knowsource.security.CurrentUserService;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

@Service
public class DocumentService {

    private static final int MAX_TITLE_LENGTH = 256;
    private static final Set<String> SUPPORTED_UPLOAD_EXTENSIONS = Set.of("txt", "md", "markdown", "pdf", "doc", "docx");
    private static final String DOCUMENT_SELECT_COLUMNS = """
            d.id, d.kb_id, d.title, d.status, d.index_status, d.oss_key, d.version, d.file_type,
            d.created_by, d.published_at, d.vectors_synced_at, d.created_at,
            latest_ingest.id AS latest_ingest_task_id,
            latest_ingest.status AS latest_ingest_status,
            latest_ingest.page_count AS latest_ingest_page_count,
            latest_ingest.extracted_page_count AS latest_ingest_extracted_page_count,
            latest_ingest.empty_page_count AS latest_ingest_empty_page_count,
            latest_ingest.table_count AS latest_ingest_table_count,
            latest_ingest.structured_table_count AS latest_ingest_structured_table_count,
            latest_ingest.failed_page_count AS latest_ingest_failed_page_count,
            latest_ingest.ocr_required_page_count AS latest_ingest_ocr_required_page_count,
            latest_ingest.ocr_applied_page_count AS latest_ingest_ocr_applied_page_count,
            latest_ingest.quality_report::text AS latest_ingest_quality_report,
            COALESCE(parent_counts.parent_chunk_count, 0) AS parent_chunk_count,
            COALESCE(child_counts.child_chunk_count, 0) AS child_chunk_count,
            latest_failed_index.id AS latest_failed_index_event_id
            """;

    private static final String LATEST_INGEST_SELECT_COLUMNS = """
            SELECT id, status, page_count, extracted_page_count, empty_page_count, table_count,
                   structured_table_count, failed_page_count, ocr_required_page_count, ocr_applied_page_count,
                   quality_report
            """;

    private static final TypeReference<List<String>> STRING_LIST_TYPE = new TypeReference<>() {
    };
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final JdbcClient jdbcClient;
    private final CurrentUserService currentUserService;
    private final SimpleTextChunker textChunker;
    private final SourceStorageService sourceStorageService;
    private final DocumentTextExtractor documentTextExtractor;
    private final DocumentIndexOutboxService documentIndexOutboxService;
    private final VectorIndexService vectorIndexService;
    private final TransactionTemplate transactionTemplate;
    private final AsyncTaskExecutor ingestExecutor;
    private final ObjectMapper objectMapper;
    private final long maxFileSizeBytes;

    public DocumentService(
            JdbcClient jdbcClient,
            CurrentUserService currentUserService,
            SimpleTextChunker textChunker,
            SourceStorageService sourceStorageService,
            DocumentTextExtractor documentTextExtractor,
            DocumentIndexOutboxService documentIndexOutboxService,
            VectorIndexService vectorIndexService,
            TransactionTemplate transactionTemplate,
            @Qualifier("ingestExecutor") AsyncTaskExecutor ingestExecutor,
            ObjectMapper objectMapper,
            @Value("${knowsource.ingest.max-file-size-bytes:52428800}") long maxFileSizeBytes) {
        this.jdbcClient = jdbcClient;
        this.currentUserService = currentUserService;
        this.textChunker = textChunker;
        this.sourceStorageService = sourceStorageService;
        this.documentTextExtractor = documentTextExtractor;
        this.documentIndexOutboxService = documentIndexOutboxService;
        this.vectorIndexService = vectorIndexService;
        this.transactionTemplate = transactionTemplate;
        this.ingestExecutor = ingestExecutor;
        this.objectMapper = objectMapper;
        this.maxFileSizeBytes = maxFileSizeBytes;
    }

    public DocumentIngestResponse ingest(String kbId, CreateDocumentRequest request) {
        CurrentUser user = currentUserService.currentUser();
        requireKbWriteAccess(kbId, user);

        String title = normalizeTitle(request.title());
        String content = normalizeContent(request.content());
        String docId = UUID.randomUUID().toString();
        String ingestTaskId = UUID.randomUUID().toString();

        DocumentResponse document = createDocumentAndTask(
                kbId, user.id(), title, docId, ingestTaskId, "inline://" + docId, "TEXT");
        try {
            ingestExecutor.execute(() -> parseAndPersistChunks(docId, document.version(), ingestTaskId, content));
        } catch (TaskRejectedException ex) {
            markIngestFailed(ingestTaskId, ex);
            throw ex;
        }

        return new DocumentIngestResponse(document, ingestTaskId, "PENDING", 0, 0);
    }

    public DocumentIngestResponse upload(String kbId, String title, MultipartFile file) {
        CurrentUser user = currentUserService.currentUser();
        requireKbWriteAccess(kbId, user);

        UploadedFile upload = validateUpload(title, file);
        String docId = UUID.randomUUID().toString();
        String ingestTaskId = UUID.randomUUID().toString();
        StoredSource storedSource = storeUploadedSource(kbId, docId, 1, upload, file);

        DocumentResponse document = createDocumentAndTask(
                kbId, user.id(), upload.title(), docId, ingestTaskId, storedSource.sourceKey(), upload.fileType());
        try {
            ingestExecutor.execute(() -> parseAndPersistStoredSource(
                    docId, document.version(), ingestTaskId, document.ossKey(), document.fileType()));
        } catch (TaskRejectedException ex) {
            markIngestFailed(ingestTaskId, ex);
            throw ex;
        }

        return new DocumentIngestResponse(document, ingestTaskId, "PENDING", 0, 0);
    }

    public DocumentIngestResponse replace(String docId, ReplaceDocumentRequest request) {
        CurrentUser user = currentUserService.currentUser();
        DocumentResponse existing = getDocumentForMember(docId, user.id());
        requireKbWriteAccess(existing.kbId(), user);

        String title = normalizeTitle(request.title());
        String content = normalizeContent(request.content());
        int nextVersion = existing.version() + 1;
        String ingestTaskId = UUID.randomUUID().toString();
        DocumentResponse document = replaceDocumentAndCreateTask(
                existing, title, "inline://" + docId + "/v" + nextVersion, "TEXT", nextVersion, ingestTaskId);
        try {
            ingestExecutor.execute(() -> parseAndPersistChunks(docId, nextVersion, ingestTaskId, content));
        } catch (TaskRejectedException ex) {
            markIngestFailed(ingestTaskId, ex);
            throw ex;
        }

        return new DocumentIngestResponse(document, ingestTaskId, "PENDING", 0, 0);
    }

    public DocumentIngestResponse replaceUpload(String docId, String title, MultipartFile file) {
        CurrentUser user = currentUserService.currentUser();
        DocumentResponse existing = getDocumentForMember(docId, user.id());
        requireKbWriteAccess(existing.kbId(), user);

        UploadedFile upload = validateUpload(title, file);
        int nextVersion = existing.version() + 1;
        String ingestTaskId = UUID.randomUUID().toString();
        StoredSource storedSource = storeUploadedSource(existing.kbId(), docId, nextVersion, upload, file);
        DocumentResponse document = replaceDocumentAndCreateTask(
                existing, upload.title(), storedSource.sourceKey(), upload.fileType(), nextVersion, ingestTaskId);
        try {
            ingestExecutor.execute(() -> parseAndPersistStoredSource(
                    docId, nextVersion, ingestTaskId, document.ossKey(), document.fileType()));
        } catch (TaskRejectedException ex) {
            markIngestFailed(ingestTaskId, ex);
            throw ex;
        }

        return new DocumentIngestResponse(document, ingestTaskId, "PENDING", 0, 0);
    }

    public List<DocumentResponse> listByKnowledgeBase(String kbId) {
        long userId = currentUserService.currentUserId();
        requireKbMember(kbId, userId);

        return jdbcClient.sql("""
                SELECT
                """ + DOCUMENT_SELECT_COLUMNS + """
                FROM documents d
                LEFT JOIN LATERAL (
                    """ + LATEST_INGEST_SELECT_COLUMNS + """
                    FROM ingest_tasks
                    WHERE doc_id = d.id
                    ORDER BY created_at DESC, id DESC
                    LIMIT 1
                ) latest_ingest ON TRUE
                LEFT JOIN LATERAL (
                    SELECT COUNT(*) AS parent_chunk_count
                    FROM chunk_parents
                    WHERE doc_id = d.id AND doc_version = d.version
                ) parent_counts ON TRUE
                LEFT JOIN LATERAL (
                    SELECT COUNT(*) AS child_chunk_count
                    FROM chunk_children
                    WHERE doc_id = d.id AND doc_version = d.version
                ) child_counts ON TRUE
                LEFT JOIN LATERAL (
                    SELECT id
                    FROM document_publish_events
                    WHERE doc_id = d.id AND doc_version = d.version AND status = 'FAILED'
                    ORDER BY updated_at DESC, created_at DESC, id DESC
                    LIMIT 1
                ) latest_failed_index ON TRUE
                WHERE d.kb_id = :kbId
                ORDER BY d.created_at DESC, d.id DESC
                """)
                .param("kbId", kbId)
                .query(this::mapDocument)
                .list();
    }

    public DocumentResponse getDocument(String docId) {
        long userId = currentUserService.currentUserId();
        CurrentUser user = currentUserService.currentUser();
        String memberJoin = "ADMIN".equals(user.globalRole())
                ? "LEFT JOIN kb_members member ON member.kb_id = d.kb_id AND member.user_id = :userId "
                : "JOIN kb_members member ON member.kb_id = d.kb_id ";

        return jdbcClient.sql("""
                SELECT
                """ + DOCUMENT_SELECT_COLUMNS + """
                FROM documents d
                """ + memberJoin + """
                LEFT JOIN LATERAL (
                    """ + LATEST_INGEST_SELECT_COLUMNS + """
                    FROM ingest_tasks
                    WHERE doc_id = d.id
                    ORDER BY created_at DESC, id DESC
                    LIMIT 1
                ) latest_ingest ON TRUE
                LEFT JOIN LATERAL (
                    SELECT COUNT(*) AS parent_chunk_count
                    FROM chunk_parents
                    WHERE doc_id = d.id AND doc_version = d.version
                ) parent_counts ON TRUE
                LEFT JOIN LATERAL (
                    SELECT COUNT(*) AS child_chunk_count
                    FROM chunk_children
                    WHERE doc_id = d.id AND doc_version = d.version
                ) child_counts ON TRUE
                LEFT JOIN LATERAL (
                    SELECT id
                    FROM document_publish_events
                    WHERE doc_id = d.id AND doc_version = d.version AND status = 'FAILED'
                    ORDER BY updated_at DESC, created_at DESC, id DESC
                    LIMIT 1
                ) latest_failed_index ON TRUE
                WHERE d.id = :docId
                  AND (member.user_id = :userId OR 'ADMIN' = :globalRole)
                """)
                .param("docId", docId)
                .param("userId", userId)
                .param("globalRole", user.globalRole())
                .query(this::mapDocument)
                .optional()
                .orElseThrow(() -> new ResourceNotFoundException("Document not found."));
    }

    public List<DocumentChunkResponse> listChunks(String docId) {
        DocumentResponse document = getDocument(docId);

        return jdbcClient.sql("""
                SELECT id, doc_id, doc_version, parent_chunk_id, content, chunk_index, page_number, chunk_type,
                       metadata::text AS metadata
                FROM chunk_children
                WHERE doc_id = :docId AND doc_version = :docVersion
                ORDER BY chunk_index ASC
                """)
                .param("docId", document.id())
                .param("docVersion", document.version())
                .query(this::mapChunk)
                .list();
    }

    public DocumentIngestResponse getLatestIngestTask(String docId) {
        DocumentResponse document = getDocument(docId);
        IngestTask task = latestIngestTask(docId);

        return new DocumentIngestResponse(
                document,
                task.id(),
                task.status(),
                document.parentChunkCount(),
                document.childChunkCount());
    }

    public DocumentIngestResponse retryLatestIngestTask(String docId) {
        CurrentUser user = currentUserService.currentUser();
        DocumentResponse document = getDocumentForMember(docId, user.id());
        requireKbWriteAccess(document.kbId(), user);
        IngestTask task = latestIngestTask(docId);
        if (!"FAILED".equals(task.status())) {
            throw new IllegalArgumentException("Only FAILED ingest tasks can be retried.");
        }
        if (document.ossKey().startsWith("inline://")) {
            throw new IllegalArgumentException("Only durable uploaded documents can be retried.");
        }

        clearChunksForVersion(document.id(), document.version());
        markIngestPending(task.id());
        try {
            ingestExecutor.execute(() -> parseAndPersistStoredSource(
                    document.id(), document.version(), task.id(), document.ossKey(), document.fileType()));
        } catch (TaskRejectedException ex) {
            markIngestFailed(task.id(), ex);
            throw ex;
        }

        return new DocumentIngestResponse(document, task.id(), "PENDING", 0, 0);
    }

    public DocumentPublishResponse publish(String docId) {
        CurrentUser user = currentUserService.currentUser();
        return createPublishEvent(docId, user);
    }

    public DocumentPublishResponse requeueIndexEvent(String docId, String eventId) {
        CurrentUser user = currentUserService.currentUser();
        DocumentResponse document = getDocumentForMember(docId, user.id());
        requireKbWriteAccess(document.kbId(), user);
        documentIndexOutboxService.requeueFailedEvent(docId, eventId);
        return new DocumentPublishResponse(
                document.id(),
                document.kbId(),
                document.version(),
                "PENDING",
                eventId,
                "Index event requeued; indexing is pending.");
    }

    public DocumentPublishResponse retryLatestFailedIndexEvent(String docId) {
        CurrentUser user = currentUserService.currentUser();
        DocumentResponse document = getDocumentForMember(docId, user.id());
        requireKbWriteAccess(document.kbId(), user);
        String eventId = document.latestFailedIndexEventId();
        if (!StringUtils.hasText(eventId)) {
            throw new IllegalArgumentException("Document has no FAILED index event to retry.");
        }
        documentIndexOutboxService.requeueFailedEvent(docId, eventId);
        return new DocumentPublishResponse(
                document.id(),
                document.kbId(),
                document.version(),
                "PENDING",
                eventId,
                "Latest failed index event requeued; indexing is pending.");
    }

    public DocumentPublishResponse archive(String docId) {
        CurrentUser user = currentUserService.currentUser();
        DocumentResponse document = getDocumentForMember(docId, user.id());
        requireKbWriteAccess(document.kbId(), user);

        transactionTemplate.executeWithoutResult(status -> {
            vectorIndexService.deleteDocumentVectors(docId);
            jdbcClient.sql("""
                    UPDATE documents
                    SET status = 'ARCHIVED',
                        index_status = 'NONE',
                        vectors_synced_at = NULL
                    WHERE id = :docId
                    """)
                    .param("docId", docId)
                    .update();
        });

        return new DocumentPublishResponse(
                document.id(),
                document.kbId(),
                document.version(),
                "NONE",
                null,
                "Document archived; vectors were removed.");
    }

    public void deleteDocument(String docId) {
        CurrentUser user = currentUserService.currentUser();
        DocumentResponse document = getDocumentForMember(docId, user.id());
        requireKbWriteAccess(document.kbId(), user);

        transactionTemplate.executeWithoutResult(status -> {
            vectorIndexService.deleteDocumentVectors(docId);
            jdbcClient.sql("""
                    DELETE FROM document_publish_events
                    WHERE doc_id = :docId
                    """)
                    .param("docId", docId)
                    .update();
            jdbcClient.sql("""
                    DELETE FROM chunk_children
                    WHERE doc_id = :docId
                    """)
                    .param("docId", docId)
                    .update();
            jdbcClient.sql("""
                    DELETE FROM chunk_parents
                    WHERE doc_id = :docId
                    """)
                    .param("docId", docId)
                    .update();
            jdbcClient.sql("""
                    DELETE FROM ingest_tasks
                    WHERE doc_id = :docId
                    """)
                    .param("docId", docId)
                    .update();
            jdbcClient.sql("""
                    DELETE FROM documents
                    WHERE id = :docId
                    """)
                    .param("docId", docId)
                    .update();
        });

        deleteSourceQuietly(document.ossKey());
    }

    public DocumentPreviewResponse preview(String docId, Integer pageNumber) {
        long userId = currentUserService.currentUserId();
        DocumentResponse document = getDocumentForMember(docId, userId);
        if (!document.ossKey().startsWith("local://") && !document.ossKey().startsWith("oss://")) {
            throw new IllegalArgumentException("Document source preview is available only for uploaded files.");
        }
        return new DocumentPreviewResponse(
                document.id(),
                document.ossKey(),
                sourceStorageService.previewUrl(document.ossKey(), 600),
                pageNumber);
    }

    public InputStream openSourcePreview(String sourceKey) throws IOException {
        long userId = currentUserService.currentUserId();
        CurrentUser user = currentUserService.currentUser();
        DocumentResponse document;
        if ("ADMIN".equals(user.globalRole())) {
            document = jdbcClient.sql("""
                    SELECT
                    """ + DOCUMENT_SELECT_COLUMNS + """
                    FROM documents d
                    LEFT JOIN LATERAL (
                        """ + LATEST_INGEST_SELECT_COLUMNS + """
                        FROM ingest_tasks
                        WHERE doc_id = d.id
                        ORDER BY created_at DESC, id DESC
                        LIMIT 1
                    ) latest_ingest ON TRUE
                    LEFT JOIN LATERAL (
                        SELECT COUNT(*) AS parent_chunk_count
                        FROM chunk_parents
                        WHERE doc_id = d.id AND doc_version = d.version
                    ) parent_counts ON TRUE
                    LEFT JOIN LATERAL (
                        SELECT COUNT(*) AS child_chunk_count
                        FROM chunk_children
                        WHERE doc_id = d.id AND doc_version = d.version
                    ) child_counts ON TRUE
                    LEFT JOIN LATERAL (
                        SELECT id
                        FROM document_publish_events
                        WHERE doc_id = d.id AND doc_version = d.version AND status = 'FAILED'
                        ORDER BY updated_at DESC, created_at DESC, id DESC
                        LIMIT 1
                    ) latest_failed_index ON TRUE
                    WHERE d.oss_key = :sourceKey
                    """)
                    .param("sourceKey", sourceKey)
                    .query(this::mapDocument)
                    .optional()
                    .orElseThrow(() -> new ResourceNotFoundException("Document source not found."));
        } else {
            document = jdbcClient.sql("""
                    SELECT
                    """ + DOCUMENT_SELECT_COLUMNS + """
                    FROM documents d
                    JOIN kb_members member ON member.kb_id = d.kb_id
                    LEFT JOIN LATERAL (
                        """ + LATEST_INGEST_SELECT_COLUMNS + """
                        FROM ingest_tasks
                        WHERE doc_id = d.id
                        ORDER BY created_at DESC, id DESC
                        LIMIT 1
                    ) latest_ingest ON TRUE
                    LEFT JOIN LATERAL (
                        SELECT COUNT(*) AS parent_chunk_count
                        FROM chunk_parents
                        WHERE doc_id = d.id AND doc_version = d.version
                    ) parent_counts ON TRUE
                    LEFT JOIN LATERAL (
                        SELECT COUNT(*) AS child_chunk_count
                        FROM chunk_children
                        WHERE doc_id = d.id AND doc_version = d.version
                    ) child_counts ON TRUE
                    LEFT JOIN LATERAL (
                        SELECT id
                        FROM document_publish_events
                        WHERE doc_id = d.id AND doc_version = d.version AND status = 'FAILED'
                        ORDER BY updated_at DESC, created_at DESC, id DESC
                        LIMIT 1
                    ) latest_failed_index ON TRUE
                    WHERE d.oss_key = :sourceKey AND member.user_id = :userId
                    """)
                    .param("sourceKey", sourceKey)
                    .param("userId", userId)
                    .query(this::mapDocument)
                    .optional()
                    .orElseThrow(() -> new ResourceNotFoundException("Document source not found."));
        }
        if (!document.ossKey().startsWith("local://") && !document.ossKey().startsWith("oss://")) {
            throw new IllegalArgumentException("Document source preview is available only for uploaded files.");
        }
        return sourceStorageService.open(document.ossKey());
    }

    private DocumentPublishResponse createPublishEvent(String docId, CurrentUser user) {
        return transactionTemplate.execute(status -> {
            DocumentResponse document = getDocumentForMember(docId, user.id());
            requireKbWriteAccess(document.kbId(), user);
            requireLatestIngestReady(docId);

            // Idempotency #1: already published and fully indexed -> no-op, no new event.
            if ("PUBLISHED".equals(document.status()) && "SYNCED".equals(document.indexStatus())) {
                return new DocumentPublishResponse(
                        document.id(),
                        document.kbId(),
                        document.version(),
                        document.indexStatus(),
                        null,
                        "Document is already published and indexed.");
            }

            // Idempotency #2: an unprocessed publish event for this version already exists
            // (e.g. rapid double-click) -> reuse it instead of enqueuing a duplicate embedding.
            String existingEventId = jdbcClient.sql("""
                    SELECT id
                    FROM document_publish_events
                    WHERE doc_id = :docId
                      AND doc_version = :docVersion
                      AND event_type = 'PUBLISH'
                      AND status IN ('PENDING', 'FAILED')
                    ORDER BY created_at DESC, id DESC
                    LIMIT 1
                    """)
                    .param("docId", docId)
                    .param("docVersion", document.version())
                    .query(String.class)
                    .optional()
                    .orElse(null);
            if (existingEventId != null) {
                return new DocumentPublishResponse(
                        document.id(),
                        document.kbId(),
                        document.version(),
                        document.indexStatus(),
                        existingEventId,
                        "Document publish already queued; indexing is pending.");
            }

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

    private DocumentResponse createDocumentAndTask(
            String kbId,
            long userId,
            String title,
            String docId,
            String ingestTaskId,
            String ossKey,
            String fileType) {
        return transactionTemplate.execute(status -> {
            DocumentResponse document = jdbcClient.sql("""
                    INSERT INTO documents (id, kb_id, title, status, index_status, oss_key, version, file_type, created_by)
                    VALUES (:id, :kbId, :title, 'DRAFT', 'NONE', :ossKey, 1, :fileType, :createdBy)
                    RETURNING id, kb_id, title, status, index_status, oss_key, version, file_type,
                              created_by, published_at, vectors_synced_at, created_at,
                              NULL AS latest_ingest_task_id,
                              NULL AS latest_ingest_status,
                              0 AS latest_ingest_page_count,
                              0 AS latest_ingest_extracted_page_count,
                              0 AS latest_ingest_empty_page_count,
                              0 AS latest_ingest_table_count,
                              0 AS latest_ingest_structured_table_count,
                              0 AS latest_ingest_failed_page_count,
                              0 AS latest_ingest_ocr_required_page_count,
                              0 AS latest_ingest_ocr_applied_page_count,
                              '{}'::jsonb::text AS latest_ingest_quality_report,
                              0 AS parent_chunk_count,
                              0 AS child_chunk_count,
                              NULL AS latest_failed_index_event_id
                    """)
                    .param("id", docId)
                    .param("kbId", kbId)
                    .param("title", title)
                    .param("ossKey", ossKey)
                    .param("fileType", fileType)
                    .param("createdBy", userId)
                    .query(this::mapDocument)
                    .single();

            jdbcClient.sql("""
                    INSERT INTO ingest_tasks (id, doc_id, status, started_at)
                    VALUES (:id, :docId, 'PENDING', NULL)
                    """)
                    .param("id", ingestTaskId)
                    .param("docId", docId)
                    .update();

            return document;
        });
    }

    private DocumentResponse replaceDocumentAndCreateTask(
            DocumentResponse existing,
            String title,
            String ossKey,
            String fileType,
            int nextVersion,
            String ingestTaskId) {
        return transactionTemplate.execute(status -> {
            vectorIndexService.deleteDocumentVectors(existing.id());
            clearChunksForVersion(existing.id(), nextVersion);

            DocumentResponse document = jdbcClient.sql("""
                    UPDATE documents
                    SET title = :title,
                        status = 'DRAFT',
                        index_status = 'NONE',
                        oss_key = :ossKey,
                        version = :version,
                        file_type = :fileType,
                        published_at = NULL,
                        vectors_synced_at = NULL
                    WHERE id = :id
                    RETURNING id, kb_id, title, status, index_status, oss_key, version, file_type,
                              created_by, published_at, vectors_synced_at, created_at,
                              NULL AS latest_ingest_task_id,
                              NULL AS latest_ingest_status,
                              0 AS latest_ingest_page_count,
                              0 AS latest_ingest_extracted_page_count,
                              0 AS latest_ingest_empty_page_count,
                              0 AS latest_ingest_table_count,
                              0 AS latest_ingest_structured_table_count,
                              0 AS latest_ingest_failed_page_count,
                              0 AS latest_ingest_ocr_required_page_count,
                              0 AS latest_ingest_ocr_applied_page_count,
                              '{}'::jsonb::text AS latest_ingest_quality_report,
                              0 AS parent_chunk_count,
                              0 AS child_chunk_count,
                              NULL AS latest_failed_index_event_id
                    """)
                    .param("id", existing.id())
                    .param("title", title)
                    .param("ossKey", ossKey)
                    .param("version", nextVersion)
                    .param("fileType", fileType)
                    .query(this::mapDocument)
                    .single();

            jdbcClient.sql("""
                    INSERT INTO ingest_tasks (id, doc_id, status, started_at)
                    VALUES (:id, :docId, 'PENDING', NULL)
                    """)
                    .param("id", ingestTaskId)
                    .param("docId", existing.id())
                    .update();

            return document;
        });
    }

    private int persistChunksAndMarkReady(
            String docId,
            int docVersion,
            String ingestTaskId,
            List<SimpleTextChunker.ParentChunk> parentChunks,
            ExtractionQualityReport qualityReport) {
        return transactionTemplate.execute(status -> {
            int childChunkCount = persistChunks(docId, docVersion, parentChunks);
            ExtractionQualityReport normalizedReport = normalizeQualityReport(qualityReport);
            jdbcClient.sql("""
                    UPDATE ingest_tasks
                    SET status = 'READY',
                        finished_at = NOW(),
                        error_message = NULL,
                        page_count = :pageCount,
                        extracted_page_count = :extractedPageCount,
                        empty_page_count = :emptyPageCount,
                        table_count = :tableCount,
                        structured_table_count = :structuredTableCount,
                        failed_page_count = :failedPageCount,
                        ocr_required_page_count = :ocrRequiredPageCount,
                        ocr_applied_page_count = :ocrAppliedPageCount,
                        quality_report = CAST(:qualityReport AS jsonb)
                    WHERE id = :id
                    """)
                    .param("id", ingestTaskId)
                    .param("pageCount", normalizedReport.pageCount())
                    .param("extractedPageCount", normalizedReport.extractedPageCount())
                    .param("emptyPageCount", normalizedReport.emptyPageCount())
                    .param("tableCount", normalizedReport.tableCount())
                    .param("structuredTableCount", normalizedReport.structuredTableCount())
                    .param("failedPageCount", normalizedReport.failedPageCount())
                    .param("ocrRequiredPageCount", normalizedReport.ocrRequiredPageCount())
                    .param("ocrAppliedPageCount", normalizedReport.ocrAppliedPageCount())
                    .param("qualityReport", qualityReportJson(normalizedReport))
                    .update();
            return childChunkCount;
        });
    }

    private void clearChunksForVersion(String docId, int docVersion) {
        transactionTemplate.executeWithoutResult(status -> {
            jdbcClient.sql("""
                    DELETE FROM chunk_children
                    WHERE doc_id = :docId AND doc_version = :docVersion
                    """)
                    .param("docId", docId)
                    .param("docVersion", docVersion)
                    .update();

            jdbcClient.sql("""
                    DELETE FROM chunk_parents
                    WHERE doc_id = :docId AND doc_version = :docVersion
                    """)
                    .param("docId", docId)
                    .param("docVersion", docVersion)
                    .update();
        });
    }

    private void parseAndPersistChunks(String docId, int docVersion, String ingestTaskId, String content) {
        try {
            markIngestParsing(ingestTaskId);
            ExtractedDocument extractedDocument = normalizeExtractedDocument(ExtractedDocument.text(content));
            List<SimpleTextChunker.ParentChunk> parentChunks = textChunker.split(extractedDocument);
            ExtractionQualityReport qualityReport = qualityReportWithChunkCounts(
                    extractedDocument.qualityReport(), extractedDocument.blocks());
            persistChunksAndMarkReady(docId, docVersion, ingestTaskId, parentChunks, qualityReport);
        } catch (RuntimeException ex) {
            markIngestFailed(ingestTaskId, ex);
        }
    }

    private void parseAndPersistStoredSource(
            String docId,
            int docVersion,
            String ingestTaskId,
            String sourceKey,
            String fileType) {
        try {
            markIngestParsing(ingestTaskId);
            ExtractedDocument extractedDocument = normalizeExtractedDocument(documentTextExtractor.extract(sourceKey, fileType));
            List<SimpleTextChunker.ParentChunk> parentChunks = textChunker.split(extractedDocument);
            ExtractionQualityReport qualityReport = qualityReportWithChunkCounts(
                    extractedDocument.qualityReport(), extractedDocument.blocks());
            persistChunksAndMarkReady(docId, docVersion, ingestTaskId, parentChunks, qualityReport);
        } catch (IOException ex) {
            markIngestFailed(ingestTaskId, new IllegalStateException("Failed to read source file.", ex));
        } catch (DocumentExtractionException ex) {
            markIngestFailed(ingestTaskId, ex);
        } catch (RuntimeException ex) {
            markIngestFailed(ingestTaskId, ex);
        }
    }

    private void markIngestParsing(String ingestTaskId) {
        transactionTemplate.executeWithoutResult(status -> jdbcClient.sql("""
                UPDATE ingest_tasks
                SET status = 'PARSING', started_at = NOW(), error_message = NULL
                WHERE id = :id
                """)
                .param("id", ingestTaskId)
                .update());
    }

    private void markIngestPending(String ingestTaskId) {
        transactionTemplate.executeWithoutResult(status -> jdbcClient.sql("""
                UPDATE ingest_tasks
                SET status = 'PENDING',
                    started_at = NULL,
                    finished_at = NULL,
                    error_message = NULL,
                    page_count = 0,
                    extracted_page_count = 0,
                    empty_page_count = 0,
                    table_count = 0,
                    structured_table_count = 0,
                    failed_page_count = 0,
                    ocr_required_page_count = 0,
                    ocr_applied_page_count = 0,
                    quality_report = '{}'::jsonb
                WHERE id = :id
                """)
                .param("id", ingestTaskId)
                .update());
    }

    private void markIngestFailed(String ingestTaskId, RuntimeException ex) {
        ExtractionQualityReport report = ex instanceof DocumentExtractionException extractionException
                ? normalizeQualityReport(extractionException.qualityReport())
                : ExtractionQualityReport.empty();
        transactionTemplate.executeWithoutResult(status -> jdbcClient.sql("""
                UPDATE ingest_tasks
                SET status = 'FAILED',
                    finished_at = NOW(),
                    error_message = :errorMessage,
                    page_count = :pageCount,
                    extracted_page_count = :extractedPageCount,
                    empty_page_count = :emptyPageCount,
                    table_count = :tableCount,
                    structured_table_count = :structuredTableCount,
                    failed_page_count = :failedPageCount,
                    ocr_required_page_count = :ocrRequiredPageCount,
                    ocr_applied_page_count = :ocrAppliedPageCount,
                    quality_report = CAST(:qualityReport AS jsonb)
                WHERE id = :id
                """)
                .param("id", ingestTaskId)
                .param("errorMessage", failureMessage(ex))
                .param("pageCount", report.pageCount())
                .param("extractedPageCount", report.extractedPageCount())
                .param("emptyPageCount", report.emptyPageCount())
                .param("tableCount", report.tableCount())
                .param("structuredTableCount", report.structuredTableCount())
                .param("failedPageCount", report.failedPageCount())
                .param("ocrRequiredPageCount", report.ocrRequiredPageCount())
                .param("ocrAppliedPageCount", report.ocrAppliedPageCount())
                .param("qualityReport", qualityReportJson(report))
                .update());
    }

    private String failureMessage(RuntimeException ex) {
        if (StringUtils.hasText(ex.getMessage())) {
            return ex.getMessage();
        }
        return ex.getClass().getSimpleName();
    }

    private ExtractionQualityReport qualityReportWithChunkCounts(
            ExtractionQualityReport report,
            List<ExtractedBlock> blocks) {
        ExtractionQualityReport normalized = normalizeQualityReport(report);
        if (normalized.pageCount() > 0) {
            return normalized;
        }
        int tableCount = (int) blocks.stream()
                .filter(block -> "TABLE".equalsIgnoreCase(block.chunkType()))
                .count();
        int structuredTableCount = (int) blocks.stream()
                .filter(block -> block.table() != null)
                .count();
        return new ExtractionQualityReport(
                0,
                0,
                0,
                tableCount,
                structuredTableCount,
                0,
                0,
                0,
                List.of(),
                List.of(),
                List.of(),
                normalized.warnings());
    }

    private ExtractionQualityReport normalizeQualityReport(ExtractionQualityReport report) {
        return report == null ? ExtractionQualityReport.empty() : report;
    }

    private int persistChunks(String docId, int docVersion, List<SimpleTextChunker.ParentChunk> parentChunks) {
        int childChunkIndex = 0;

        for (SimpleTextChunker.ParentChunk parentChunk : parentChunks) {
            String parentChunkId = docId + "-v" + docVersion + "-p-" + parentChunk.parentIndex();
            jdbcClient.sql("""
                    INSERT INTO chunk_parents (id, doc_id, doc_version, content, page_number, metadata)
                    VALUES (:id, :docId, :docVersion, :content, :pageNumber, CAST(:metadata AS jsonb))
                    """)
                    .param("id", parentChunkId)
                    .param("docId", docId)
                    .param("docVersion", docVersion)
                    .param("content", parentChunk.content())
                    .param("pageNumber", parentChunk.pageNumber())
                    .param("metadata", parentMetadataJson(parentChunk))
                    .update();

            for (SimpleTextChunker.ChildChunk childChunk : parentChunk.children()) {
                jdbcClient.sql("""
                        INSERT INTO chunk_children
                            (id, doc_id, doc_version, parent_chunk_id, content, chunk_index, page_number, chunk_type, metadata)
                        VALUES
                            (:id, :docId, :docVersion, :parentChunkId, :content, :chunkIndex, :pageNumber, :chunkType,
                             CAST(:metadata AS jsonb))
                        """)
                        .param("id", docId + "-v" + docVersion + "-c-" + childChunkIndex)
                        .param("docId", docId)
                        .param("docVersion", docVersion)
                        .param("parentChunkId", parentChunkId)
                        .param("content", childChunk.content())
                        .param("chunkIndex", childChunkIndex)
                        .param("pageNumber", childChunk.pageNumber())
                        .param("chunkType", childChunk.chunkType())
                        .param("metadata", childMetadataJson(childChunk))
                        .update();
                childChunkIndex++;
            }
        }

        return childChunkIndex;
    }

    private String parentMetadataJson(SimpleTextChunker.ParentChunk parentChunk) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("blockIndex", parentChunk.blockIndex());
        metadata.put("sectionPath", parentChunk.sectionPath());
        metadata.put("tableCaption", parentChunk.tableCaption());
        metadata.put("table", tableMetadata(parentChunk.table()));
        metadata.put("startOffset", parentChunk.startOffset());
        metadata.put("endOffset", parentChunk.endOffset());
        metadata.put("parentIndex", parentChunk.parentIndex());
        return json(metadata);
    }

    private String childMetadataJson(SimpleTextChunker.ChildChunk childChunk) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("blockIndex", childChunk.blockIndex());
        metadata.put("sectionPath", childChunk.sectionPath());
        metadata.put("tableCaption", childChunk.tableCaption());
        metadata.put("table", tableMetadata(childChunk.table()));
        metadata.put("startOffset", childChunk.startOffset());
        metadata.put("endOffset", childChunk.endOffset());
        return json(metadata);
    }

    private Map<String, Object> tableMetadata(ExtractedTable table) {
        if (table == null) {
            return null;
        }
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("headers", table.headers());
        metadata.put("rows", table.rows());
        metadata.put("markdown", table.markdown());
        metadata.put("rowCount", table.rowCount());
        metadata.put("columnCount", table.columnCount());
        return metadata;
    }

    private String qualityReportJson(ExtractionQualityReport report) {
        ExtractionQualityReport normalized = normalizeQualityReport(report);
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("emptyPages", normalized.emptyPages());
        value.put("failedPages", normalized.failedPages());
        value.put("ocrRequiredPages", normalized.ocrRequiredPages());
        value.put("warnings", normalized.warnings());
        return json(value);
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("Failed to serialize document metadata.", ex);
        }
    }

    private UploadedFile validateUpload(String title, MultipartFile file) {
        String normalizedTitle = normalizeTitle(title);
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("Document file is required.");
        }
        if (file.getSize() > maxFileSizeBytes) {
            throw new IllegalArgumentException("Document file exceeds the configured size limit.");
        }

        String originalFilename = file.getOriginalFilename();
        String extension = fileExtension(originalFilename);
        if (!SUPPORTED_UPLOAD_EXTENSIONS.contains(extension)) {
            throw new IllegalArgumentException("Unsupported document file type. Supported types: txt, md, markdown, pdf, doc, docx.");
        }

        return new UploadedFile(
                normalizedTitle,
                StringUtils.hasText(originalFilename) ? originalFilename.trim() : "source." + extension,
                normalizeContentType(file.getContentType()),
                fileType(extension));
    }

    private StoredSource storeUploadedSource(
            String kbId,
            String docId,
            int docVersion,
            UploadedFile upload,
            MultipartFile file) {
        try (var inputStream = file.getInputStream()) {
            return sourceStorageService.store(
                    kbId,
                    docId,
                    docVersion,
                    upload.originalFilename(),
                    upload.contentType(),
                    inputStream);
        } catch (IOException ex) {
            throw new IllegalArgumentException("Failed to store document file.");
        }
    }

    private void deleteSourceQuietly(String sourceKey) {
        if (!StringUtils.hasText(sourceKey)
                || (!sourceKey.startsWith("local://") && !sourceKey.startsWith("oss://"))) {
            return;
        }
        try {
            sourceStorageService.delete(sourceKey);
        } catch (IOException ignored) {
            // The database row is the source of truth for deletion; source cleanup can be retried operationally.
        }
    }

    private String fileExtension(String originalFilename) {
        if (!StringUtils.hasText(originalFilename)) {
            throw new IllegalArgumentException("Document filename is required.");
        }

        String filename = originalFilename.trim();
        int dotIndex = filename.lastIndexOf('.');
        if (dotIndex < 0 || dotIndex == filename.length() - 1) {
            throw new IllegalArgumentException("Document filename must include a supported extension.");
        }
        return filename.substring(dotIndex + 1).toLowerCase(Locale.ROOT);
    }

    private String fileType(String extension) {
        if ("md".equals(extension) || "markdown".equals(extension)) {
            return "MARKDOWN";
        }
        if ("pdf".equals(extension)) {
            return "PDF";
        }
        if ("doc".equals(extension) || "docx".equals(extension)) {
            return "WORD";
        }
        return "TEXT";
    }

    private String normalizeContentType(String contentType) {
        return StringUtils.hasText(contentType) ? contentType.trim() : "application/octet-stream";
    }

    private void requireKbMember(String kbId, long userId) {
        if ("ADMIN".equals(currentUserService.currentUser().globalRole())) {
            Long exists = jdbcClient.sql("SELECT COUNT(*) FROM knowledge_bases WHERE id = :kbId")
                    .param("kbId", kbId)
                    .query(Long.class)
                    .single();
            if (exists == 0) {
                throw new ResourceNotFoundException("Knowledge base not found.");
            }
            return;
        }
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

    private void requireKbWriteAccess(String kbId, CurrentUser user) {
        if ("ADMIN".equals(user.globalRole())) {
            requireKbMember(kbId, user.id());
            return;
        }
        String memberRole = jdbcClient.sql("""
                SELECT role
                FROM kb_members
                WHERE kb_id = :kbId AND user_id = :userId
                """)
                .param("kbId", kbId)
                .param("userId", user.id())
                .query(String.class)
                .optional()
                .orElseThrow(() -> new ResourceNotFoundException("Knowledge base not found."));
        if (!Set.of("OWNER", "EDITOR").contains(memberRole)) {
            throw new AccessDeniedException("Write access is required.");
        }
    }

    private DocumentResponse getDocumentForMember(String docId, long userId) {
        CurrentUser user = currentUserService.currentUser();
        if ("ADMIN".equals(user.globalRole())) {
            return jdbcClient.sql("""
                    SELECT
                    """ + DOCUMENT_SELECT_COLUMNS + """
                    FROM documents d
                    LEFT JOIN LATERAL (
                        """ + LATEST_INGEST_SELECT_COLUMNS + """
                        FROM ingest_tasks
                        WHERE doc_id = d.id
                        ORDER BY created_at DESC, id DESC
                        LIMIT 1
                    ) latest_ingest ON TRUE
                    LEFT JOIN LATERAL (
                        SELECT COUNT(*) AS parent_chunk_count
                        FROM chunk_parents
                        WHERE doc_id = d.id AND doc_version = d.version
                    ) parent_counts ON TRUE
                    LEFT JOIN LATERAL (
                        SELECT COUNT(*) AS child_chunk_count
                        FROM chunk_children
                        WHERE doc_id = d.id AND doc_version = d.version
                    ) child_counts ON TRUE
                    LEFT JOIN LATERAL (
                        SELECT id
                        FROM document_publish_events
                        WHERE doc_id = d.id AND doc_version = d.version AND status = 'FAILED'
                        ORDER BY updated_at DESC, created_at DESC, id DESC
                        LIMIT 1
                    ) latest_failed_index ON TRUE
                    WHERE d.id = :docId
                    """)
                    .param("docId", docId)
                    .query(this::mapDocument)
                    .optional()
                    .orElseThrow(() -> new ResourceNotFoundException("Document not found."));
        }
        return jdbcClient.sql("""
                SELECT
                """ + DOCUMENT_SELECT_COLUMNS + """
                FROM documents d
                JOIN kb_members member ON member.kb_id = d.kb_id
                LEFT JOIN LATERAL (
                    """ + LATEST_INGEST_SELECT_COLUMNS + """
                    FROM ingest_tasks
                    WHERE doc_id = d.id
                    ORDER BY created_at DESC, id DESC
                    LIMIT 1
                ) latest_ingest ON TRUE
                LEFT JOIN LATERAL (
                    SELECT COUNT(*) AS parent_chunk_count
                    FROM chunk_parents
                    WHERE doc_id = d.id AND doc_version = d.version
                ) parent_counts ON TRUE
                LEFT JOIN LATERAL (
                    SELECT COUNT(*) AS child_chunk_count
                    FROM chunk_children
                    WHERE doc_id = d.id AND doc_version = d.version
                ) child_counts ON TRUE
                LEFT JOIN LATERAL (
                    SELECT id
                    FROM document_publish_events
                    WHERE doc_id = d.id AND doc_version = d.version AND status = 'FAILED'
                    ORDER BY updated_at DESC, created_at DESC, id DESC
                    LIMIT 1
                ) latest_failed_index ON TRUE
                WHERE d.id = :docId AND member.user_id = :userId
                """)
                .param("docId", docId)
                .param("userId", userId)
                .query(this::mapDocument)
                .optional()
                .orElseThrow(() -> new ResourceNotFoundException("Document not found."));
    }

    private void requireLatestIngestReady(String docId) {
        String latestStatus = latestIngestTask(docId).status();

        if (!"READY".equals(latestStatus)) {
            throw new IllegalArgumentException("Document ingest task is not READY.");
        }
    }

    private IngestTask latestIngestTask(String docId) {
        return jdbcClient.sql("""
                SELECT id, status
                FROM ingest_tasks
                WHERE doc_id = :docId
                ORDER BY created_at DESC, id DESC
                LIMIT 1
                """)
                .param("docId", docId)
                .query(DocumentService::mapIngestTask)
                .optional()
                .orElseThrow(() -> new IllegalArgumentException("Document has no ingest task."));
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

    private ExtractedDocument normalizeExtractedDocument(ExtractedDocument document) {
        if (document == null || document.blocks() == null) {
            throw new IllegalArgumentException("Document content is required.");
        }
        List<ExtractedBlock> blocks = document.blocks().stream()
                .filter(block -> block != null && StringUtils.hasText(block.content()))
                .map(block -> new ExtractedBlock(
                        block.content().trim(),
                        block.pageNumber(),
                        StringUtils.hasText(block.chunkType()) ? block.chunkType().trim().toUpperCase(Locale.ROOT) : "TEXT",
                        block.blockIndex(),
                        block.sectionPath(),
                        StringUtils.hasText(block.tableCaption()) ? block.tableCaption().trim() : null,
                        block.table()))
                .toList();
        if (blocks.isEmpty()) {
            throw new IllegalArgumentException("Document content is required.");
        }
        return new ExtractedDocument(blocks, document.qualityReport());
    }

    private DocumentResponse mapDocument(ResultSet rs, int rowNum) throws SQLException {
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
                rs.getTimestamp("created_at").toLocalDateTime(),
                rs.getString("latest_ingest_task_id"),
                rs.getString("latest_ingest_status"),
                rs.getInt("parent_chunk_count"),
                rs.getInt("child_chunk_count"),
                rs.getString("latest_failed_index_event_id"),
                mapQualityReport(rs));
    }

    private DocumentChunkResponse mapChunk(ResultSet rs, int rowNum) throws SQLException {
        Map<String, Object> metadata = parseMetadata(rs.getString("metadata"));
        Map<String, Object> table = nestedMap(metadata.get("table"));
        return new DocumentChunkResponse(
                rs.getString("id"),
                rs.getString("doc_id"),
                rs.getInt("doc_version"),
                rs.getString("parent_chunk_id"),
                rs.getString("content"),
                rs.getInt("chunk_index"),
                (Integer) rs.getObject("page_number"),
                rs.getString("chunk_type"),
                stringList(metadata.get("sectionPath")),
                stringValue(metadata.get("tableCaption")),
                integerValue(metadata.get("startOffset")),
                integerValue(metadata.get("endOffset")),
                stringValue(table.get("markdown")),
                integerValue(table.get("rowCount")),
                integerValue(table.get("columnCount")));
    }

    private DocumentQualityReportResponse mapQualityReport(ResultSet rs) throws SQLException {
        Map<String, Object> detail = parseMetadata(rs.getString("latest_ingest_quality_report"));
        return new DocumentQualityReportResponse(
                rs.getInt("latest_ingest_page_count"),
                rs.getInt("latest_ingest_extracted_page_count"),
                rs.getInt("latest_ingest_empty_page_count"),
                rs.getInt("latest_ingest_table_count"),
                rs.getInt("latest_ingest_structured_table_count"),
                rs.getInt("latest_ingest_failed_page_count"),
                rs.getInt("latest_ingest_ocr_required_page_count"),
                rs.getInt("latest_ingest_ocr_applied_page_count"),
                integerList(detail.get("emptyPages")),
                integerList(detail.get("failedPages")),
                integerList(detail.get("ocrRequiredPages")),
                stringList(detail.get("warnings")));
    }

    private Map<String, Object> parseMetadata(String metadataJson) {
        if (!StringUtils.hasText(metadataJson)) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(metadataJson, MAP_TYPE);
        } catch (JsonProcessingException ex) {
            return Map.of();
        }
    }

    private Map<String, Object> nestedMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            map.forEach((key, mapValue) -> result.put(String.valueOf(key), mapValue));
            return result;
        }
        return Map.of();
    }

    private List<String> stringList(Object value) {
        if (value == null) {
            return List.of();
        }
        try {
            return objectMapper.convertValue(value, STRING_LIST_TYPE);
        } catch (IllegalArgumentException ex) {
            return List.of();
        }
    }

    private List<Integer> integerList(Object value) {
        if (value instanceof List<?> values) {
            return values.stream()
                    .map(this::integerValue)
                    .filter(number -> number != null)
                    .toList();
        }
        return List.of();
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private Integer integerValue(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String string && StringUtils.hasText(string)) {
            try {
                return Integer.parseInt(string);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private static IngestTask mapIngestTask(ResultSet rs, int rowNum) throws SQLException {
        return new IngestTask(rs.getString("id"), rs.getString("status"));
    }

    private record IngestTask(String id, String status) {
    }

    private record UploadedFile(String title, String originalFilename, String contentType, String fileType) {
    }

    private static LocalDateTime toLocalDateTime(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toLocalDateTime();
    }
}

package com.knowsource.document;

import java.io.IOException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.Locale;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.knowsource.user.DemoUserService;
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

    private final JdbcClient jdbcClient;
    private final DemoUserService demoUserService;
    private final SimpleTextChunker textChunker;
    private final SourceStorageService sourceStorageService;
    private final DocumentTextExtractor documentTextExtractor;
    private final TransactionTemplate transactionTemplate;
    private final AsyncTaskExecutor ingestExecutor;
    private final long maxFileSizeBytes;

    public DocumentService(
            JdbcClient jdbcClient,
            DemoUserService demoUserService,
            SimpleTextChunker textChunker,
            SourceStorageService sourceStorageService,
            DocumentTextExtractor documentTextExtractor,
            TransactionTemplate transactionTemplate,
            @Qualifier("ingestExecutor") AsyncTaskExecutor ingestExecutor,
            @Value("${knowsource.ingest.max-file-size-bytes:52428800}") long maxFileSizeBytes) {
        this.jdbcClient = jdbcClient;
        this.demoUserService = demoUserService;
        this.textChunker = textChunker;
        this.sourceStorageService = sourceStorageService;
        this.documentTextExtractor = documentTextExtractor;
        this.transactionTemplate = transactionTemplate;
        this.ingestExecutor = ingestExecutor;
        this.maxFileSizeBytes = maxFileSizeBytes;
    }

    public DocumentIngestResponse ingest(String kbId, CreateDocumentRequest request) {
        long userId = demoUserService.currentUserId();
        requireKbMember(kbId, userId);

        String title = normalizeTitle(request.title());
        String content = normalizeContent(request.content());
        String docId = UUID.randomUUID().toString();
        String ingestTaskId = UUID.randomUUID().toString();

        DocumentResponse document = createDocumentAndTask(
                kbId, userId, title, docId, ingestTaskId, "inline://" + docId, "TEXT");
        try {
            ingestExecutor.execute(() -> parseAndPersistChunks(docId, document.version(), ingestTaskId, content));
        } catch (TaskRejectedException ex) {
            markIngestFailed(ingestTaskId, ex);
            throw ex;
        }

        return new DocumentIngestResponse(document, ingestTaskId, "PENDING", 0, 0);
    }

    public DocumentIngestResponse upload(String kbId, String title, MultipartFile file) {
        long userId = demoUserService.currentUserId();
        requireKbMember(kbId, userId);

        UploadedFile upload = validateUpload(title, file);
        String docId = UUID.randomUUID().toString();
        String ingestTaskId = UUID.randomUUID().toString();
        StoredSource storedSource = storeUploadedSource(kbId, docId, 1, upload, file);

        DocumentResponse document = createDocumentAndTask(
                kbId, userId, upload.title(), docId, ingestTaskId, storedSource.sourceKey(), upload.fileType());
        try {
            ingestExecutor.execute(() -> parseAndPersistStoredSource(
                    docId, document.version(), ingestTaskId, document.ossKey(), document.fileType()));
        } catch (TaskRejectedException ex) {
            markIngestFailed(ingestTaskId, ex);
            throw ex;
        }

        return new DocumentIngestResponse(document, ingestTaskId, "PENDING", 0, 0);
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

    public DocumentIngestResponse getLatestIngestTask(String docId) {
        DocumentResponse document = getDocument(docId);
        IngestTask task = jdbcClient.sql("""
                SELECT id, status
                FROM ingest_tasks
                WHERE doc_id = :docId
                ORDER BY created_at DESC, id DESC
                LIMIT 1
                """)
                .param("docId", docId)
                .query(DocumentService::mapIngestTask)
                .optional()
                .orElseThrow(() -> new ResourceNotFoundException("Ingest task not found."));

        long parentChunkCount = jdbcClient.sql("""
                SELECT COUNT(*)
                FROM chunk_parents
                WHERE doc_id = :docId AND doc_version = :docVersion
                """)
                .param("docId", docId)
                .param("docVersion", document.version())
                .query(Long.class)
                .single();
        long childChunkCount = jdbcClient.sql("""
                SELECT COUNT(*)
                FROM chunk_children
                WHERE doc_id = :docId AND doc_version = :docVersion
                """)
                .param("docId", docId)
                .param("docVersion", document.version())
                .query(Long.class)
                .single();

        return new DocumentIngestResponse(
                document,
                task.id(),
                task.status(),
                Math.toIntExact(parentChunkCount),
                Math.toIntExact(childChunkCount));
    }

    public DocumentPublishResponse publish(String docId) {
        long userId = demoUserService.currentUserId();
        return createPublishEvent(docId, userId);
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
                              created_by, published_at, vectors_synced_at, created_at
                    """)
                    .param("id", docId)
                    .param("kbId", kbId)
                    .param("title", title)
                    .param("ossKey", ossKey)
                    .param("fileType", fileType)
                    .param("createdBy", userId)
                    .query(DocumentService::mapDocument)
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

    private void parseAndPersistChunks(String docId, int docVersion, String ingestTaskId, String content) {
        try {
            markIngestParsing(ingestTaskId);
            List<SimpleTextChunker.ParentChunk> parentChunks = textChunker.split(content);
            persistChunksAndMarkReady(docId, docVersion, ingestTaskId, parentChunks);
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
            String content = normalizeContent(documentTextExtractor.extract(sourceKey, fileType));
            List<SimpleTextChunker.ParentChunk> parentChunks = textChunker.split(content);
            persistChunksAndMarkReady(docId, docVersion, ingestTaskId, parentChunks);
        } catch (IOException ex) {
            markIngestFailed(ingestTaskId, new IllegalStateException("Failed to read source file.", ex));
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

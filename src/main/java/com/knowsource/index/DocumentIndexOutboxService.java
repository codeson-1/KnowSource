package com.knowsource.index;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

@Service
public class DocumentIndexOutboxService {

    private static final Logger log = LoggerFactory.getLogger(DocumentIndexOutboxService.class);

    private final JdbcClient jdbcClient;
    private final VectorIndexService vectorIndexService;
    private final TransactionTemplate transactionTemplate;
    private final long syncingTimeoutSeconds;
    private final int maxAttempts;
    private final long initialRetryDelaySeconds;
    private final long maxRetryDelaySeconds;

    public DocumentIndexOutboxService(
            JdbcClient jdbcClient,
            VectorIndexService vectorIndexService,
            TransactionTemplate transactionTemplate,
            @Value("${knowsource.index.syncing-timeout-seconds:1800}") long syncingTimeoutSeconds,
            @Value("${knowsource.index.retry.max-attempts:5}") int maxAttempts,
            @Value("${knowsource.index.retry.initial-delay-seconds:30}") long initialRetryDelaySeconds,
            @Value("${knowsource.index.retry.max-delay-seconds:300}") long maxRetryDelaySeconds) {
        this.jdbcClient = jdbcClient;
        this.vectorIndexService = vectorIndexService;
        this.transactionTemplate = transactionTemplate;
        this.syncingTimeoutSeconds = syncingTimeoutSeconds;
        this.maxAttempts = Math.max(1, maxAttempts);
        this.initialRetryDelaySeconds = Math.max(1L, initialRetryDelaySeconds);
        this.maxRetryDelaySeconds = Math.max(this.initialRetryDelaySeconds, maxRetryDelaySeconds);
    }

    public boolean processNextPendingEvent() {
        if (!vectorIndexService.hasIndexingBackend()) {
            return false;
        }

        recoverStaleSyncingEvents();
        Optional<IndexEvent> event = claimNextEvent();
        if (event.isEmpty()) {
            return false;
        }

        try {
            vectorIndexService.reindexPublishedDocument(event.get().kbId(), event.get().docId(), event.get().docVersion());
            markSucceeded(event.get());
        } catch (RuntimeException ex) {
            markFailed(event.get(), ex);
        }

        return true;
    }

    public void requeueFailedEvent(String docId, String eventId) {
        transactionTemplate.executeWithoutResult(status -> {
            IndexEventForRequeue event = jdbcClient.sql("""
                    SELECT id, doc_id, kb_id, doc_version, event_type, status
                    FROM document_publish_events
                    WHERE id = :eventId AND doc_id = :docId
                    FOR UPDATE
                    """)
                    .param("eventId", eventId)
                    .param("docId", docId)
                    .query(DocumentIndexOutboxService::mapEventForRequeue)
                    .optional()
                    .orElseThrow(() -> new IllegalArgumentException("Index event not found."));

            if (!"FAILED".equals(event.status())) {
                throw new IllegalArgumentException("Only FAILED index events can be requeued.");
            }

            jdbcClient.sql("""
                    UPDATE document_publish_events
                    SET status = 'PENDING',
                        error_message = NULL,
                        attempt_count = 0,
                        next_retry_at = NULL,
                        locked_at = NULL,
                        locked_by = NULL,
                        updated_at = NOW()
                    WHERE id = :eventId
                    """)
                    .param("eventId", eventId)
                    .update();

            jdbcClient.sql("""
                    UPDATE documents
                    SET index_status = 'PENDING',
                        vectors_synced_at = NULL
                    WHERE id = :docId AND version = :docVersion
                    """)
                    .param("docId", event.docId())
                    .param("docVersion", event.docVersion())
                    .update();
        });
    }

    private void recoverStaleSyncingEvents() {
        transactionTemplate.executeWithoutResult(status -> jdbcClient.sql("""
                WITH recovered AS (
                    UPDATE document_publish_events
                    SET status = 'FAILED',
                        error_message = 'Indexing lock expired before completion.',
                        attempt_count = attempt_count + 1,
                        next_retry_at = NOW(),
                        locked_at = NULL,
                        locked_by = NULL,
                        updated_at = NOW()
                    WHERE status = 'SYNCING'
                      AND locked_at IS NOT NULL
                      AND locked_at <= NOW() - (:timeoutSeconds * INTERVAL '1 second')
                    RETURNING doc_id, doc_version
                )
                UPDATE documents d
                SET index_status = 'FAILED'
                FROM recovered r
                WHERE d.id = r.doc_id AND d.version = r.doc_version
                """)
                .param("timeoutSeconds", syncingTimeoutSeconds)
                .update());
    }

    private Optional<IndexEvent> claimNextEvent() {
        return transactionTemplate.execute(status -> {
            Optional<IndexEvent> event = jdbcClient.sql("""
                    SELECT id, doc_id, kb_id, doc_version, event_type
                    FROM document_publish_events
                    WHERE status IN ('PENDING', 'FAILED')
                      AND attempt_count < :maxAttempts
                      AND (next_retry_at IS NULL OR next_retry_at <= NOW())
                    ORDER BY created_at ASC
                    LIMIT 1
                    FOR UPDATE SKIP LOCKED
                    """)
                    .param("maxAttempts", maxAttempts)
                    .query(DocumentIndexOutboxService::mapEvent)
                    .optional();

            event.ifPresent(indexEvent -> {
                jdbcClient.sql("""
                        UPDATE document_publish_events
                        SET status = 'SYNCING',
                            locked_at = NOW(),
                            locked_by = :lockedBy,
                            updated_at = NOW(),
                            error_message = NULL
                        WHERE id = :id
                        """)
                        .param("lockedBy", "knowsource-local")
                        .param("id", indexEvent.id())
                        .update();

                jdbcClient.sql("""
                        UPDATE documents
                        SET index_status = 'SYNCING'
                        WHERE id = :docId AND version = :docVersion
                        """)
                        .param("docId", indexEvent.docId())
                        .param("docVersion", indexEvent.docVersion())
                        .update();
            });

            return event;
        });
    }

    private void markSucceeded(IndexEvent event) {
        transactionTemplate.executeWithoutResult(status -> {
            jdbcClient.sql("""
                    UPDATE document_publish_events
                    SET status = 'PROCESSED',
                        processed_at = NOW(),
                        locked_at = NULL,
                        locked_by = NULL,
                        updated_at = NOW(),
                        error_message = NULL
                    WHERE id = :id
                    """)
                    .param("id", event.id())
                    .update();

            jdbcClient.sql("""
                    UPDATE documents
                    SET index_status = 'SYNCED',
                        vectors_synced_at = NOW()
                    WHERE id = :docId AND version = :docVersion
                    """)
                    .param("docId", event.docId())
                    .param("docVersion", event.docVersion())
                    .update();
        });
    }

    private void markFailed(IndexEvent event, RuntimeException ex) {
        log.warn("Document index event failed. eventId={}, docId={}, docVersion={}",
                event.id(), event.docId(), event.docVersion(), ex);
        transactionTemplate.executeWithoutResult(status -> {
            int currentAttemptCount = jdbcClient.sql("""
                    SELECT attempt_count
                    FROM document_publish_events
                    WHERE id = :id
                    """)
                    .param("id", event.id())
                    .query(Integer.class)
                    .single();

            jdbcClient.sql("""
                    UPDATE document_publish_events
                    SET status = 'FAILED',
                        error_message = :errorMessage,
                        attempt_count = attempt_count + 1,
                        next_retry_at = NOW() + (:retryDelaySeconds * INTERVAL '1 second'),
                        locked_at = NULL,
                        locked_by = NULL,
                        updated_at = NOW()
                    WHERE id = :id
                    """)
                    .param("id", event.id())
                    .param("errorMessage", failureMessage(ex))
                    .param("retryDelaySeconds", retryDelaySeconds(currentAttemptCount))
                    .update();

            jdbcClient.sql("""
                    UPDATE documents
                    SET index_status = 'FAILED'
                    WHERE id = :docId AND version = :docVersion
                    """)
                    .param("docId", event.docId())
                    .param("docVersion", event.docVersion())
                    .update();
        });
    }

    private static IndexEvent mapEvent(ResultSet rs, int rowNum) throws SQLException {
        return new IndexEvent(
                rs.getString("id"),
                rs.getString("doc_id"),
                rs.getString("kb_id"),
                rs.getInt("doc_version"),
                rs.getString("event_type"));
    }

    private static IndexEventForRequeue mapEventForRequeue(ResultSet rs, int rowNum) throws SQLException {
        return new IndexEventForRequeue(
                rs.getString("id"),
                rs.getString("doc_id"),
                rs.getString("kb_id"),
                rs.getInt("doc_version"),
                rs.getString("event_type"),
                rs.getString("status"));
    }

    private record IndexEventForRequeue(
            String id,
            String docId,
            String kbId,
            int docVersion,
            String eventType,
            String status) {
    }

    private static String failureMessage(RuntimeException ex) {
        // 遍历完整 cause 链而非只取 root cause:中间层(如 AiProviderException)的消息
        // 往往是"AI embedding call failed."这类无信息文本,真正的根因在更内层。
        // 旧实现只拼 root cause,当 root cause 消息为空时整条根因都会丢失。
        StringBuilder builder = new StringBuilder();
        Throwable current = ex;
        while (current != null) {
            if (StringUtils.hasText(current.getMessage())) {
                if (!builder.isEmpty()) {
                    builder.append(" -> ");
                }
                builder.append(current.getClass().getSimpleName())
                        .append(": ")
                        .append(current.getMessage());
            }
            Throwable cause = current.getCause();
            if (cause == null || cause == current) {
                break;
            }
            current = cause;
        }
        if (!builder.isEmpty()) {
            return builder.toString();
        }
        return ex.getClass().getSimpleName();
    }

    private long retryDelaySeconds(int currentAttemptCount) {
        long multiplier = 1L << Math.min(Math.max(0, currentAttemptCount), 30);
        long delay;
        try {
            delay = Math.multiplyExact(initialRetryDelaySeconds, multiplier);
        } catch (ArithmeticException ex) {
            delay = Long.MAX_VALUE;
        }
        return Math.min(delay, maxRetryDelaySeconds);
    }
}

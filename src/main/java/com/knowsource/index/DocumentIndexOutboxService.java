package com.knowsource.index;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

@Service
public class DocumentIndexOutboxService {

    private static final int MAX_ATTEMPTS = 5;

    private final JdbcClient jdbcClient;
    private final VectorIndexService vectorIndexService;
    private final TransactionTemplate transactionTemplate;
    private final long syncingTimeoutSeconds;

    public DocumentIndexOutboxService(
            JdbcClient jdbcClient,
            VectorIndexService vectorIndexService,
            TransactionTemplate transactionTemplate,
            @Value("${knowsource.index.syncing-timeout-seconds:300}") long syncingTimeoutSeconds) {
        this.jdbcClient = jdbcClient;
        this.vectorIndexService = vectorIndexService;
        this.transactionTemplate = transactionTemplate;
        this.syncingTimeoutSeconds = syncingTimeoutSeconds;
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
                    .param("maxAttempts", MAX_ATTEMPTS)
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
                    SET status = 'DONE',
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
        transactionTemplate.executeWithoutResult(status -> {
            jdbcClient.sql("""
                    UPDATE document_publish_events
                    SET status = 'FAILED',
                        error_message = :errorMessage,
                        attempt_count = attempt_count + 1,
                        next_retry_at = NOW() + INTERVAL '30 seconds',
                        locked_at = NULL,
                        locked_by = NULL,
                        updated_at = NOW()
                    WHERE id = :id
                    """)
                    .param("id", event.id())
                    .param("errorMessage", failureMessage(ex))
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

    private static String failureMessage(RuntimeException ex) {
        if (StringUtils.hasText(ex.getMessage())) {
            return ex.getMessage();
        }
        return ex.getClass().getSimpleName();
    }
}

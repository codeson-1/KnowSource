-- P0-2: enforce publish idempotency at the database level.
-- At most one PENDING publish/reindex event may exist per
-- (doc_id, doc_version, event_type), so a double-click cannot enqueue a
-- duplicate embedding. FAILED events are intentionally not covered: a stale
-- SYNCING event recovered to FAILED may legitimately coexist with a fresh
-- PENDING publish for the same version, and PROCESSED history is unconstrained
-- so re-publishing after a completed cycle still works.
CREATE UNIQUE INDEX IF NOT EXISTS uq_publish_events_active
    ON document_publish_events (doc_id, doc_version, event_type)
    WHERE status = 'PENDING';

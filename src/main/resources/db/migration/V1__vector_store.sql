CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE IF NOT EXISTS vector_store (
    id          UUID PRIMARY KEY,
    content     TEXT,
    metadata    JSONB,
    embedding   vector(1024),
    kb_id       VARCHAR(36),
    doc_id      VARCHAR(36),
    status      VARCHAR(16),
    doc_version INT
);

CREATE INDEX IF NOT EXISTS idx_vector_kb_status_doc_ver
    ON vector_store (kb_id, status, doc_id, doc_version);

CREATE INDEX IF NOT EXISTS idx_vector_embedding_hnsw
    ON vector_store USING hnsw (embedding vector_cosine_ops);

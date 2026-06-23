CREATE TABLE IF NOT EXISTS users (
    id            BIGSERIAL PRIMARY KEY,
    username      VARCHAR(64) NOT NULL UNIQUE,
    password_hash VARCHAR(128) NOT NULL,
    email         VARCHAR(128),
    global_role   VARCHAR(16) NOT NULL DEFAULT 'VIEWER',
    token_version INT NOT NULL DEFAULT 1,
    created_at    TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS refresh_tokens (
    id         VARCHAR(36) PRIMARY KEY,
    user_id    BIGINT NOT NULL REFERENCES users(id),
    token_hash VARCHAR(128) NOT NULL UNIQUE,
    expires_at TIMESTAMP NOT NULL,
    revoked_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_refresh_tokens_user
    ON refresh_tokens(user_id);

CREATE TABLE IF NOT EXISTS knowledge_bases (
    id          VARCHAR(36) PRIMARY KEY,
    name        VARCHAR(128) NOT NULL,
    description TEXT,
    owner_id    BIGINT NOT NULL REFERENCES users(id),
    created_at  TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_knowledge_bases_owner
    ON knowledge_bases(owner_id);

CREATE TABLE IF NOT EXISTS kb_members (
    id      BIGSERIAL PRIMARY KEY,
    kb_id   VARCHAR(36) NOT NULL REFERENCES knowledge_bases(id),
    user_id BIGINT NOT NULL REFERENCES users(id),
    role    VARCHAR(16) NOT NULL,
    UNIQUE(kb_id, user_id)
);

CREATE INDEX IF NOT EXISTS idx_kb_members_user
    ON kb_members(user_id);

CREATE TABLE IF NOT EXISTS documents (
    id                VARCHAR(36) PRIMARY KEY,
    kb_id             VARCHAR(36) NOT NULL REFERENCES knowledge_bases(id),
    title             VARCHAR(256) NOT NULL,
    status            VARCHAR(16) NOT NULL DEFAULT 'DRAFT',
    index_status      VARCHAR(16) NOT NULL DEFAULT 'NONE',
    oss_key           VARCHAR(512) NOT NULL,
    version           INT NOT NULL DEFAULT 1,
    file_type         VARCHAR(16),
    created_by        BIGINT NOT NULL REFERENCES users(id),
    published_at      TIMESTAMP,
    vectors_synced_at TIMESTAMP,
    created_at        TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_documents_kb_status_index
    ON documents(kb_id, status, index_status);

CREATE INDEX IF NOT EXISTS idx_documents_created_by
    ON documents(created_by);

CREATE TABLE IF NOT EXISTS ingest_tasks (
    id            VARCHAR(36) PRIMARY KEY,
    doc_id        VARCHAR(36) NOT NULL REFERENCES documents(id),
    status        VARCHAR(16) NOT NULL,
    error_message TEXT,
    started_at    TIMESTAMP,
    finished_at   TIMESTAMP,
    created_at    TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_ingest_tasks_doc
    ON ingest_tasks(doc_id);

CREATE INDEX IF NOT EXISTS idx_ingest_tasks_status_created
    ON ingest_tasks(status, created_at);

CREATE TABLE IF NOT EXISTS chunk_parents (
    id          VARCHAR(64) PRIMARY KEY,
    doc_id      VARCHAR(36) NOT NULL REFERENCES documents(id),
    doc_version INT NOT NULL DEFAULT 1,
    content     TEXT NOT NULL,
    page_number INT
);

CREATE INDEX IF NOT EXISTS idx_chunk_parents_doc_ver
    ON chunk_parents(doc_id, doc_version);

CREATE TABLE IF NOT EXISTS chunk_children (
    id              VARCHAR(64) PRIMARY KEY,
    doc_id          VARCHAR(36) NOT NULL REFERENCES documents(id),
    doc_version     INT NOT NULL DEFAULT 1,
    parent_chunk_id VARCHAR(64) NOT NULL REFERENCES chunk_parents(id),
    content         TEXT NOT NULL,
    chunk_index     INT NOT NULL,
    page_number     INT,
    chunk_type      VARCHAR(16) NOT NULL DEFAULT 'TEXT',
    UNIQUE(doc_id, doc_version, chunk_index)
);

CREATE INDEX IF NOT EXISTS idx_chunk_children_doc_ver
    ON chunk_children(doc_id, doc_version);

CREATE INDEX IF NOT EXISTS idx_chunk_children_parent
    ON chunk_children(parent_chunk_id);

CREATE TABLE IF NOT EXISTS document_publish_events (
    id            VARCHAR(36) PRIMARY KEY,
    doc_id        VARCHAR(36) NOT NULL REFERENCES documents(id),
    kb_id         VARCHAR(36) NOT NULL REFERENCES knowledge_bases(id),
    doc_version   INT NOT NULL,
    event_type    VARCHAR(16) NOT NULL,
    status        VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    error_message TEXT,
    attempt_count INT NOT NULL DEFAULT 0,
    next_retry_at TIMESTAMP,
    locked_at     TIMESTAMP,
    locked_by     VARCHAR(64),
    created_at    TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMP NOT NULL DEFAULT NOW(),
    processed_at  TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_publish_events_sched
    ON document_publish_events(status, next_retry_at, created_at)
    WHERE status IN ('PENDING', 'FAILED');

CREATE INDEX IF NOT EXISTS idx_publish_events_doc_ver
    ON document_publish_events(doc_id, doc_version);

CREATE TABLE IF NOT EXISTS qa_traces (
    id                        VARCHAR(36) PRIMARY KEY,
    session_id                VARCHAR(36),
    user_id                   BIGINT NOT NULL REFERENCES users(id),
    kb_id                     VARCHAR(36) NOT NULL REFERENCES knowledge_bases(id),
    query                     TEXT NOT NULL,
    rewritten_query           TEXT,
    retrieved_chunks          JSONB,
    answer                    TEXT,
    retrieval_ms              INT,
    llm_ms                    INT,
    rewrite_llm_ms            INT,
    generation_first_token_ms INT,
    total_ms                  INT,
    token_usage               JSONB,
    rag_profile               VARCHAR(16),
    created_at                TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_qa_traces_kb_created
    ON qa_traces(kb_id, created_at);

CREATE INDEX IF NOT EXISTS idx_qa_traces_user_created
    ON qa_traces(user_id, created_at);

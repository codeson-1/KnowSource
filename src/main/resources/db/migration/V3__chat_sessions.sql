CREATE TABLE IF NOT EXISTS chat_sessions (
    id         VARCHAR(36) PRIMARY KEY,
    user_id    BIGINT NOT NULL REFERENCES users(id),
    kb_id      VARCHAR(36) NOT NULL REFERENCES knowledge_bases(id),
    title      VARCHAR(256),
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_chat_sessions_user_kb_updated
    ON chat_sessions(user_id, kb_id, updated_at DESC);

CREATE TABLE IF NOT EXISTS chat_messages (
    id          BIGSERIAL PRIMARY KEY,
    session_id  VARCHAR(36) NOT NULL REFERENCES chat_sessions(id),
    role        VARCHAR(16) NOT NULL,
    content     TEXT NOT NULL,
    token_count INT,
    created_at  TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_chat_messages_session_created
    ON chat_messages(session_id, created_at, id);

CREATE INDEX IF NOT EXISTS idx_qa_traces_session_created
    ON qa_traces(session_id, created_at);

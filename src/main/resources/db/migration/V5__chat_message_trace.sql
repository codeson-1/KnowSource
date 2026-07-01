ALTER TABLE chat_messages
    ADD COLUMN IF NOT EXISTS qa_trace_id VARCHAR(36);

CREATE INDEX IF NOT EXISTS idx_chat_messages_qa_trace
    ON chat_messages(qa_trace_id);

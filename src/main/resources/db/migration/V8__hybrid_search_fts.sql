ALTER TABLE vector_store ADD COLUMN IF NOT EXISTS content_tokens TEXT;
ALTER TABLE vector_store ADD COLUMN IF NOT EXISTS doc_title VARCHAR(256);

ALTER TABLE vector_store
    ADD COLUMN IF NOT EXISTS content_tsv tsvector
    GENERATED ALWAYS AS (to_tsvector('simple', coalesce(content_tokens, ''))) STORED;

CREATE INDEX IF NOT EXISTS idx_vector_store_content_tsv_gin
    ON vector_store USING gin (content_tsv);

CREATE INDEX IF NOT EXISTS idx_vector_store_published_content_tsv_gin
    ON vector_store USING gin (content_tsv)
    WHERE status = 'published';

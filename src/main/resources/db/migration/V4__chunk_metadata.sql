ALTER TABLE chunk_parents
    ADD COLUMN IF NOT EXISTS metadata JSONB;

ALTER TABLE chunk_children
    ADD COLUMN IF NOT EXISTS metadata JSONB;

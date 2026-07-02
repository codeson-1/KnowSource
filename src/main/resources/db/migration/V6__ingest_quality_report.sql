ALTER TABLE ingest_tasks
    ADD COLUMN IF NOT EXISTS page_count INT NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS extracted_page_count INT NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS empty_page_count INT NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS table_count INT NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS structured_table_count INT NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS failed_page_count INT NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS ocr_required_page_count INT NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS ocr_applied_page_count INT NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS quality_report JSONB NOT NULL DEFAULT '{}'::jsonb;

CREATE INDEX IF NOT EXISTS idx_ingest_tasks_quality
    ON ingest_tasks(doc_id, page_count, empty_page_count, failed_page_count, table_count);

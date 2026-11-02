CREATE TABLE news_source_run (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    source VARCHAR(80) NOT NULL,
    started_at TIMESTAMPTZ NOT NULL,
    finished_at TIMESTAMPTZ NOT NULL,
    status VARCHAR(16) NOT NULL CHECK (status IN ('SUCCESS', 'PARTIAL', 'FAILED')),
    fetched_items INTEGER NOT NULL DEFAULT 0 CHECK (fetched_items >= 0),
    inserted_items INTEGER NOT NULL DEFAULT 0 CHECK (inserted_items >= 0),
    summarized_items INTEGER NOT NULL DEFAULT 0 CHECK (summarized_items >= 0),
    error_code VARCHAR(80),
    error_detail VARCHAR(300),
    CONSTRAINT ck_news_source_run_time CHECK (finished_at >= started_at)
);

CREATE INDEX ix_news_source_run_source_finished
    ON news_source_run (source, finished_at DESC);
CREATE INDEX ix_news_source_run_failed
    ON news_source_run (finished_at DESC)
    WHERE status = 'FAILED';

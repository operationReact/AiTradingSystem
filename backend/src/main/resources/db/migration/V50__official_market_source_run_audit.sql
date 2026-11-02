-- V3-02: durable operator evidence for scheduled/manual official-source retrieval attempts.
CREATE TABLE official_market_source_run (
    run_id UUID PRIMARY KEY,
    source_family VARCHAR(40) NOT NULL CHECK (source_family IN (
        'NIFTY50_CONSTITUENTS',
        'NIFTY50_HISTORY',
        'INDIA_VIX_HISTORY',
        'SECTOR_INDEX_HISTORY'
    )),
    source_variant VARCHAR(80) NOT NULL DEFAULT '',
    source_url VARCHAR(2048) NOT NULL,
    started_at TIMESTAMPTZ NOT NULL,
    finished_at TIMESTAMPTZ NOT NULL,
    status VARCHAR(12) NOT NULL CHECK (status IN ('SUCCEEDED', 'FAILED')),
    snapshot_id UUID REFERENCES official_market_source_snapshot(snapshot_id),
    error_code VARCHAR(80),
    error_detail VARCHAR(300),
    CONSTRAINT ck_official_source_run_time CHECK (finished_at >= started_at),
    CONSTRAINT ck_official_source_run_outcome CHECK (
        (status = 'SUCCEEDED' AND snapshot_id IS NOT NULL AND error_code IS NULL)
        OR (status = 'FAILED' AND snapshot_id IS NULL AND error_code IS NOT NULL)
    )
);

CREATE INDEX ix_official_source_run_latest
    ON official_market_source_run
       (source_family, source_variant, finished_at DESC, run_id DESC);

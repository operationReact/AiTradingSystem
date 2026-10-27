-- Phase 3 provenance-aware news records.
-- Separate from V3__news_intelligence.sql to preserve existing applied migration history.
CREATE TABLE news_item (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    source VARCHAR(80) NOT NULL,
    publisher VARCHAR(160) NOT NULL,
    source_url TEXT NOT NULL,
    published_at TIMESTAMPTZ,
    fetched_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    title VARCHAR(500) NOT NULL,
    normalized_content TEXT NOT NULL,
    content_hash CHAR(64) NOT NULL UNIQUE,
    linked_symbols TEXT[] NOT NULL DEFAULT '{}',
    ingestion_status VARCHAR(24) NOT NULL DEFAULT 'INGESTED'
        CHECK (ingestion_status IN ('INGESTED', 'SUMMARIZED')),
    CONSTRAINT news_item_source_url_https CHECK (source_url LIKE 'https://%')
);

CREATE TABLE news_summary (
    news_id UUID PRIMARY KEY REFERENCES news_item(id) ON DELETE CASCADE,
    provider VARCHAR(40) NOT NULL,
    model VARCHAR(100) NOT NULL,
    summary TEXT NOT NULL,
    generated_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_news_item_fetched_at ON news_item (fetched_at DESC);
CREATE INDEX idx_news_item_published_at ON news_item (published_at DESC);
CREATE INDEX idx_news_item_symbols ON news_item USING GIN (linked_symbols);

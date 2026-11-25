-- V2-05 structured AI-news evidence and immutable request/response provenance.
ALTER TABLE news_analyses
    ADD COLUMN direction_score NUMERIC(4,3),
    ADD COLUMN materiality NUMERIC(4,3),
    ADD COLUMN horizon VARCHAR(24),
    ADD COLUMN novelty NUMERIC(4,3),
    ADD COLUMN contradiction BOOLEAN,
    ADD COLUMN ambiguity BOOLEAN,
    ADD COLUMN provider_request_id VARCHAR(200),
    ADD COLUMN source_available_at TIMESTAMPTZ,
    ADD COLUMN prompt_hash CHAR(64),
    ADD COLUMN request_fingerprint CHAR(64),
    ADD COLUMN response_fingerprint CHAR(64);

ALTER TABLE news_analyses
    ADD CONSTRAINT ck_news_analyses_direction_score
        CHECK (direction_score IS NULL OR (direction_score >= -1 AND direction_score <= 1)),
    ADD CONSTRAINT ck_news_analyses_materiality
        CHECK (materiality IS NULL OR (materiality >= 0 AND materiality <= 1)),
    ADD CONSTRAINT ck_news_analyses_novelty
        CHECK (novelty IS NULL OR (novelty >= 0 AND novelty <= 1)),
    ADD CONSTRAINT ck_news_analyses_horizon
        CHECK (horizon IS NULL OR horizon IN ('INTRADAY', 'MULTI_DAY', 'LONGER_TERM', 'UNKNOWN'));

CREATE INDEX ix_news_analyses_provider_request_id
    ON news_analyses (provider_request_id)
    WHERE provider_request_id IS NOT NULL;

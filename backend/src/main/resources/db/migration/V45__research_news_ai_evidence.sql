-- Structured AI evidence attached to the authoritative provenance-aware news_item feed.
-- Evidence becomes research-visible only at generated_at; it may never backdate to source publication/fetch time.
CREATE TABLE news_ai_evidence (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    news_id UUID NOT NULL REFERENCES news_item(id) ON DELETE CASCADE,
    provider VARCHAR(40) NOT NULL,
    model VARCHAR(100) NOT NULL,
    provider_request_id VARCHAR(200),
    source_available_at TIMESTAMPTZ NOT NULL,
    generated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    prompt_hash CHAR(64) NOT NULL,
    request_fingerprint CHAR(64) NOT NULL UNIQUE,
    response_fingerprint CHAR(64) NOT NULL,
    sentiment VARCHAR(20) NOT NULL,
    direction_score NUMERIC(4,3) NOT NULL,
    category VARCHAR(40) NOT NULL,
    confidence NUMERIC(4,3) NOT NULL,
    materiality NUMERIC(4,3) NOT NULL,
    horizon VARCHAR(24) NOT NULL,
    novelty NUMERIC(4,3) NOT NULL,
    contradiction BOOLEAN NOT NULL,
    ambiguity BOOLEAN NOT NULL,
    impacted_symbols TEXT[] NOT NULL DEFAULT '{}',
    evidence TEXT NOT NULL,
    risks TEXT[] NOT NULL DEFAULT '{}',
    input_tokens INTEGER,
    output_tokens INTEGER,
    total_tokens INTEGER,
    latency_ms INTEGER NOT NULL,
    CONSTRAINT ck_news_ai_evidence_source_time CHECK (source_available_at <= generated_at),
    CONSTRAINT ck_news_ai_evidence_direction CHECK (direction_score BETWEEN -1 AND 1),
    CONSTRAINT ck_news_ai_evidence_confidence CHECK (confidence BETWEEN 0 AND 1),
    CONSTRAINT ck_news_ai_evidence_materiality CHECK (materiality BETWEEN 0 AND 1),
    CONSTRAINT ck_news_ai_evidence_novelty CHECK (novelty BETWEEN 0 AND 1),
    CONSTRAINT ck_news_ai_evidence_horizon CHECK (horizon IN ('INTRADAY','MULTI_DAY','LONGER_TERM','UNKNOWN')),
    CONSTRAINT ck_news_ai_evidence_input_tokens CHECK (input_tokens IS NULL OR input_tokens >= 0),
    CONSTRAINT ck_news_ai_evidence_output_tokens CHECK (output_tokens IS NULL OR output_tokens >= 0),
    CONSTRAINT ck_news_ai_evidence_total_tokens CHECK (total_tokens IS NULL OR total_tokens >= 0),
    CONSTRAINT ck_news_ai_evidence_latency CHECK (latency_ms >= 0)
);

CREATE INDEX ix_news_ai_evidence_generated_at ON news_ai_evidence (generated_at, id);
CREATE INDEX ix_news_ai_evidence_news_id ON news_ai_evidence (news_id, generated_at DESC);
CREATE INDEX ix_news_ai_evidence_provider_request ON news_ai_evidence (provider_request_id)
    WHERE provider_request_id IS NOT NULL;

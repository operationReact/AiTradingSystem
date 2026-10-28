CREATE TABLE research_scan_run (
    id UUID PRIMARY KEY,
    interval VARCHAR(32) NOT NULL,
    status VARCHAR(20) NOT NULL,
    requested_limit INTEGER NOT NULL CHECK (requested_limit > 0 AND requested_limit <= 500),
    scanned_count INTEGER NOT NULL DEFAULT 0,
    candidate_count INTEGER NOT NULL DEFAULT 0,
    strategy_set VARCHAR(200) NOT NULL,
    started_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at TIMESTAMPTZ,
    error_code VARCHAR(80),
    error_message VARCHAR(500)
);

CREATE TABLE research_scan_candidate (
    id BIGSERIAL PRIMARY KEY,
    scan_run_id UUID NOT NULL REFERENCES research_scan_run(id) ON DELETE CASCADE,
    instrument_id BIGINT NOT NULL,
    trading_symbol VARCHAR(120) NOT NULL,
    exchange VARCHAR(20) NOT NULL,
    interval VARCHAR(32) NOT NULL,
    strategy VARCHAR(80) NOT NULL,
    score NUMERIC(8,6) NOT NULL CHECK (score >= 0 AND score <= 1),
    entry_price NUMERIC(19,4) NOT NULL,
    stop_price NUMERIC(19,4) NOT NULL,
    target_price NUMERIC(19,4) NOT NULL,
    signal_candle_time TIMESTAMPTZ NOT NULL,
    reasons TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (scan_run_id, instrument_id, strategy, signal_candle_time)
);

CREATE INDEX idx_research_scan_run_started_at ON research_scan_run(started_at DESC);
CREATE INDEX idx_research_scan_candidate_run_score ON research_scan_candidate(scan_run_id, score DESC);
CREATE INDEX idx_research_scan_candidate_instrument ON research_scan_candidate(instrument_id, signal_candle_time DESC);

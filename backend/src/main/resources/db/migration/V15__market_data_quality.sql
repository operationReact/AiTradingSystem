CREATE TABLE market_data_quality (
    id BIGSERIAL PRIMARY KEY,
    instrument_id BIGINT NOT NULL,
    interval VARCHAR(20) NOT NULL,
    window_from TIMESTAMPTZ NOT NULL,
    window_to TIMESTAMPTZ NOT NULL,
    expected_candles INTEGER NOT NULL CHECK (expected_candles >= 0),
    actual_candles INTEGER NOT NULL CHECK (actual_candles >= 0),
    missing_candles INTEGER NOT NULL CHECK (missing_candles >= 0),
    duplicate_candles INTEGER NOT NULL DEFAULT 0 CHECK (duplicate_candles >= 0),
    zero_volume_candles INTEGER NOT NULL DEFAULT 0 CHECK (zero_volume_candles >= 0),
    status VARCHAR(16) NOT NULL CHECK (status IN ('HEALTHY','DEGRADED','INCOMPLETE','FAILED')),
    gap_ranges JSONB NOT NULL DEFAULT '[]'::jsonb,
    evaluated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_market_data_quality_window UNIQUE (instrument_id, interval, window_from, window_to),
    CONSTRAINT ck_market_data_quality_window CHECK (window_to > window_from)
);
CREATE INDEX idx_market_data_quality_instrument ON market_data_quality (instrument_id, interval, evaluated_at DESC);
CREATE INDEX idx_market_data_quality_status ON market_data_quality (status, evaluated_at DESC);

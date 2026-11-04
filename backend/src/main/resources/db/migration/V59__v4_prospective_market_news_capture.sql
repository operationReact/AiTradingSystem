-- V4-09: freeze the exact Java-persisted market/news source rows before any
-- claimed same-input PAPER/SHADOW model decision. No inference or capital authority.
CREATE TABLE v4_matched_source_capture (
    session_id UUID NOT NULL REFERENCES v4_matched_session(session_id),
    case_key VARCHAR(96) NOT NULL
        CHECK (case_key ~ '^[A-Za-z0-9][A-Za-z0-9._-]{2,95}$'),
    candle_id BIGINT NOT NULL,
    news_id UUID NOT NULL,
    symbol VARCHAR(30) NOT NULL,
    market_available_at TIMESTAMPTZ NOT NULL,
    news_available_at TIMESTAMPTZ NOT NULL,
    market_snapshot_fingerprint CHAR(64) NOT NULL
        CHECK (market_snapshot_fingerprint ~ '^[a-f0-9]{64}$'),
    news_snapshot_fingerprint CHAR(64) NOT NULL
        CHECK (news_snapshot_fingerprint ~ '^[a-f0-9]{64}$'),
    input_fingerprint CHAR(64) NOT NULL
        CHECK (input_fingerprint ~ '^[a-f0-9]{64}$'),
    capture_fingerprint CHAR(64) NOT NULL
        CHECK (capture_fingerprint ~ '^[a-f0-9]{64}$'),
    captured_at TIMESTAMPTZ NOT NULL,
    captured_by VARCHAR(120) NOT NULL,
    can_authorize_capital BOOLEAN NOT NULL DEFAULT FALSE
        CHECK (can_authorize_capital = FALSE),
    PRIMARY KEY (session_id, case_key),
    CHECK (market_available_at <= captured_at),
    CHECK (news_available_at <= captured_at)
);

CREATE INDEX ix_v4_matched_source_recent
    ON v4_matched_source_capture(session_id, captured_at DESC, case_key DESC);

-- This is a database-backed evidence snapshot. Existing upstream data rows can
-- subsequently be updated; the immutable capture hashes preserve the bytes seen
-- during intake. Deliberately no FK to the mutable/retained source tables: deleting
-- a candle or news row later must not cascade-delete immutable capture history.
-- They do NOT establish provider signatures or source usage rights.

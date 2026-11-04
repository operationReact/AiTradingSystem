-- V4-09: point-in-time feature snapshot for actual research model inference.
CREATE TABLE v4_matched_feature_capture (
    session_id UUID NOT NULL,
    case_key VARCHAR(96) NOT NULL,
    previous_candle_id BIGINT NOT NULL,
    news_ai_evidence_id UUID NOT NULL,
    source_input_fingerprint CHAR(64) NOT NULL CHECK (source_input_fingerprint ~ '^[a-f0-9]{64}$'),
    feature_payload_fingerprint CHAR(64) NOT NULL CHECK (feature_payload_fingerprint ~ '^[a-f0-9]{64}$'),
    feature_as_of TIMESTAMPTZ NOT NULL,
    latest_feature_available_at TIMESTAMPTZ NOT NULL,
    market_return_1 NUMERIC(30,16) NOT NULL,
    market_range_pct NUMERIC(30,16) NOT NULL CHECK (market_range_pct >= 0),
    market_volume_change_1 NUMERIC(30,16) NOT NULL,
    news_direction_score NUMERIC(4,3) NOT NULL CHECK (news_direction_score BETWEEN -1 AND 1),
    news_confidence NUMERIC(4,3) NOT NULL CHECK (news_confidence BETWEEN 0 AND 1),
    news_materiality NUMERIC(4,3) NOT NULL CHECK (news_materiality BETWEEN 0 AND 1),
    recorded_at TIMESTAMPTZ NOT NULL,
    recorded_by VARCHAR(120) NOT NULL,
    can_authorize_capital BOOLEAN NOT NULL DEFAULT FALSE CHECK (can_authorize_capital = FALSE),
    PRIMARY KEY (session_id, case_key),
    FOREIGN KEY (session_id, case_key)
        REFERENCES v4_matched_source_capture(session_id, case_key),
    CHECK (latest_feature_available_at <= feature_as_of),
    CHECK (feature_as_of <= recorded_at)
);

CREATE INDEX ix_v4_matched_feature_capture_recent
    ON v4_matched_feature_capture(session_id, feature_as_of DESC, case_key);

-- No future labels, provider-signature claims, broker orders or capital authority.

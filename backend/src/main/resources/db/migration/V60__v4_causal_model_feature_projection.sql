-- V4-09: freeze the exact numeric features used after V59's source capture.
-- This stores no copyrighted text and cannot attest exchange/news rights or execute orders.
CREATE TABLE v4_matched_feature_projection (
    session_id UUID NOT NULL,
    case_key VARCHAR(96) NOT NULL,
    source_capture_fingerprint CHAR(64) NOT NULL
        CHECK (source_capture_fingerprint ~ '^[a-f0-9]{64}$'),
    previous_candle_id BIGINT NOT NULL,
    news_ai_evidence_id UUID NOT NULL,
    feature_schema_version VARCHAR(32) NOT NULL DEFAULT 'features.market.v1'
        CHECK (feature_schema_version = 'features.market.v1'),
    feature_as_of TIMESTAMPTZ NOT NULL,
    latest_feature_available_at TIMESTAMPTZ NOT NULL,
    feature_json JSONB NOT NULL CHECK (jsonb_typeof(feature_json) = 'object'),
    feature_payload_fingerprint CHAR(64) NOT NULL
        CHECK (feature_payload_fingerprint ~ '^[a-f0-9]{64}$'),
    projected_at TIMESTAMPTZ NOT NULL,
    projected_by VARCHAR(120) NOT NULL,
    can_authorize_capital BOOLEAN NOT NULL DEFAULT FALSE
        CHECK (can_authorize_capital = FALSE),
    PRIMARY KEY (session_id, case_key),
    FOREIGN KEY (session_id, case_key)
        REFERENCES v4_matched_source_capture(session_id, case_key),
    CHECK (latest_feature_available_at <= feature_as_of),
    CHECK (feature_as_of <= projected_at)
);

CREATE INDEX ix_v4_feature_projection_session
    ON v4_matched_feature_projection(session_id, projected_at DESC, case_key);

-- Only the immutable capture ID is constrained, not mutable retained candle
-- or copyrighted news tables. Captured numeric evidence must survive deletion
-- of upstream feed rows; future projection REPLAY checks their current hashes.

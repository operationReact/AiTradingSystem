-- V4-09: append-only Java runtime intake for matched PAPER/SHADOW candidate decisions.
-- This does not execute orders, promote models or assert independent source custody.
CREATE TABLE v4_matched_session (
    session_id UUID PRIMARY KEY,
    contract_version VARCHAR(40) NOT NULL DEFAULT 'v4-matched-runtime-intake.v1'
        CHECK (contract_version = 'v4-matched-runtime-intake.v1'),
    target_spec_fingerprint CHAR(64) NOT NULL CHECK (target_spec_fingerprint ~ '^[a-f0-9]{64}$'),
    enrollment_fingerprint CHAR(64) NOT NULL CHECK (enrollment_fingerprint ~ '^[a-f0-9]{64}$'),
    created_at TIMESTAMPTZ NOT NULL,
    created_by VARCHAR(120) NOT NULL,
    can_authorize_capital BOOLEAN NOT NULL DEFAULT FALSE CHECK (can_authorize_capital = FALSE)
);

CREATE TABLE v4_matched_participant (
    session_id UUID NOT NULL REFERENCES v4_matched_session(session_id),
    model_version_id UUID NOT NULL REFERENCES model_version(version_id),
    role VARCHAR(20) NOT NULL CHECK (role IN ('PAPER_CHAMPION','SHADOW_CHALLENGER')),
    model_fingerprint CHAR(64) NOT NULL CHECK (model_fingerprint ~ '^[a-f0-9]{64}$'),
    PRIMARY KEY(session_id, model_version_id)
);

CREATE UNIQUE INDEX ux_v4_matched_single_champion
    ON v4_matched_participant(session_id) WHERE role = 'PAPER_CHAMPION';

CREATE TABLE v4_matched_case (
    session_id UUID NOT NULL REFERENCES v4_matched_session(session_id),
    case_key VARCHAR(96) NOT NULL CHECK (case_key ~ '^[A-Za-z0-9][A-Za-z0-9._-]{2,95}$'),
    symbol VARCHAR(30) NOT NULL,
    decision_at TIMESTAMPTZ NOT NULL,
    market_available_at TIMESTAMPTZ NOT NULL,
    news_available_at TIMESTAMPTZ NOT NULL,
    market_source_fingerprint CHAR(64) NOT NULL CHECK (market_source_fingerprint ~ '^[a-f0-9]{64}$'),
    news_source_fingerprint CHAR(64) NOT NULL CHECK (news_source_fingerprint ~ '^[a-f0-9]{64}$'),
    input_fingerprint CHAR(64) NOT NULL CHECK (input_fingerprint ~ '^[a-f0-9]{64}$'),
    case_fingerprint CHAR(64) NOT NULL CHECK (case_fingerprint ~ '^[a-f0-9]{64}$'),
    recorded_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY(session_id, case_key),
    CHECK (market_available_at <= decision_at AND news_available_at <= decision_at)
);

CREATE TABLE v4_matched_decision (
    session_id UUID NOT NULL,
    case_key VARCHAR(96) NOT NULL,
    model_version_id UUID NOT NULL,
    predicted_at TIMESTAMPTZ NOT NULL,
    action VARCHAR(8) NOT NULL CHECK (action IN ('ENTER','WAIT','ABSTAIN')),
    decision_fingerprint CHAR(64) NOT NULL CHECK (decision_fingerprint ~ '^[a-f0-9]{64}$'),
    PRIMARY KEY(session_id, case_key, model_version_id),
    FOREIGN KEY(session_id, case_key) REFERENCES v4_matched_case(session_id, case_key),
    FOREIGN KEY(session_id, model_version_id) REFERENCES v4_matched_participant(session_id, model_version_id)
);

CREATE INDEX ix_v4_matched_case_recent ON v4_matched_case(session_id, decision_at DESC, case_key DESC);
-- No update/delete API exists. Replays are read-only; source and candidate identities cannot be replaced.

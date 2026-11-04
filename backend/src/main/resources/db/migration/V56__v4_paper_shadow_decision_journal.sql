-- V4-09: durable read-only-influence PAPER/SHADOW model decision journal.
-- No trigger or API endpoint in this migration can place broker orders or authorize capital.
CREATE TABLE v4_paper_shadow_session (
    session_id UUID PRIMARY KEY,
    contract_version VARCHAR(40) NOT NULL DEFAULT 'v4-paper-shadow-journal.v1'
        CHECK (contract_version = 'v4-paper-shadow-journal.v1'),
    source_kind VARCHAR(16) NOT NULL CHECK (source_kind IN ('CI_SYNTHETIC','RUNTIME_EXPORT')),
    cohort_fingerprint CHAR(64) NOT NULL CHECK (cohort_fingerprint ~ '^[a-f0-9]{64}$'),
    target_fingerprint CHAR(64) NOT NULL CHECK (target_fingerprint ~ '^[a-f0-9]{64}$'),
    session_fingerprint CHAR(64) NOT NULL UNIQUE CHECK (session_fingerprint ~ '^[a-f0-9]{64}$'),
    created_at TIMESTAMPTZ NOT NULL,
    created_by VARCHAR(120) NOT NULL,
    can_authorize_capital BOOLEAN NOT NULL DEFAULT FALSE CHECK (can_authorize_capital = FALSE)
);

CREATE TABLE v4_paper_shadow_model (
    session_id UUID NOT NULL REFERENCES v4_paper_shadow_session(session_id),
    model_version_id UUID NOT NULL REFERENCES model_version(version_id),
    model_fingerprint CHAR(64) NOT NULL CHECK (model_fingerprint ~ '^[a-f0-9]{64}$'),
    role VARCHAR(24) NOT NULL CHECK (role IN ('PAPER_CHAMPION','SHADOW_CHALLENGER')),
    PRIMARY KEY (session_id, model_version_id),
    UNIQUE (session_id, model_version_id, role)
);
CREATE UNIQUE INDEX uq_v4_paper_shadow_one_champion
    ON v4_paper_shadow_model(session_id) WHERE role = 'PAPER_CHAMPION';

CREATE TABLE v4_paper_shadow_case (
    case_id UUID PRIMARY KEY,
    session_id UUID NOT NULL REFERENCES v4_paper_shadow_session(session_id),
    case_key VARCHAR(96) NOT NULL CHECK (case_key ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{2,95}$'),
    symbol VARCHAR(40) NOT NULL,
    decision_at TIMESTAMPTZ NOT NULL,
    market_available_at TIMESTAMPTZ NOT NULL,
    news_available_at TIMESTAMPTZ NOT NULL,
    market_fingerprint CHAR(64) NOT NULL CHECK (market_fingerprint ~ '^[a-f0-9]{64}$'),
    news_fingerprint CHAR(64) NOT NULL CHECK (news_fingerprint ~ '^[a-f0-9]{64}$'),
    input_fingerprint CHAR(64) NOT NULL CHECK (input_fingerprint ~ '^[a-f0-9]{64}$'),
    case_fingerprint CHAR(64) NOT NULL CHECK (case_fingerprint ~ '^[a-f0-9]{64}$'),
    recorded_at TIMESTAMPTZ NOT NULL,
    UNIQUE (session_id, case_key),
    UNIQUE (session_id, case_id),
    CHECK (market_available_at <= decision_at AND news_available_at <= decision_at)
);
CREATE INDEX ix_v4_paper_shadow_case_session_time
    ON v4_paper_shadow_case(session_id, decision_at, case_key);

CREATE TABLE v4_paper_shadow_decision (
    decision_id UUID PRIMARY KEY,
    session_id UUID NOT NULL,
    case_id UUID NOT NULL,
    model_version_id UUID NOT NULL,
    role VARCHAR(24) NOT NULL CHECK (role IN ('PAPER_CHAMPION','SHADOW_CHALLENGER')),
    predicted_at TIMESTAMPTZ NOT NULL,
    action VARCHAR(12) NOT NULL CHECK (action IN ('ENTER','WAIT','ABSTAIN')),
    model_evidence_fingerprint CHAR(64) NOT NULL CHECK (model_evidence_fingerprint ~ '^[a-f0-9]{64}$'),
    decision_fingerprint CHAR(64) NOT NULL CHECK (decision_fingerprint ~ '^[a-f0-9]{64}$'),
    FOREIGN KEY (session_id, case_id) REFERENCES v4_paper_shadow_case(session_id, case_id),
    FOREIGN KEY (session_id, model_version_id, role)
        REFERENCES v4_paper_shadow_model(session_id, model_version_id, role),
    UNIQUE (case_id, model_version_id),
    CHECK (predicted_at IS NOT NULL)
);

CREATE TABLE v4_paper_shadow_order_link (
    case_id UUID PRIMARY KEY REFERENCES v4_paper_shadow_case(case_id),
    order_id UUID NOT NULL UNIQUE REFERENCES paper_order(id),
    linked_at TIMESTAMPTZ NOT NULL,
    linked_by VARCHAR(120) NOT NULL
);
-- PAPER order links are explicitly champion-only via the transactional service;
-- database FK and UNIQUE constraints prevent non-PAPER and duplicate order links.

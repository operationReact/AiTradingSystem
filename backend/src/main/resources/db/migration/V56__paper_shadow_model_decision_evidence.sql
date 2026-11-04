-- V4-09: append-only, operator-declared simultaneous model-decision evidence.
-- This records model research observations and optional links to EXISTING PAPER orders.
-- No record in these tables may authorize capital, create a broker order or promote a model.
CREATE TABLE paper_shadow_decision_case (
    case_id UUID PRIMARY KEY,
    case_key VARCHAR(96) NOT NULL UNIQUE
        CHECK (case_key ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{2,95}$'),
    contract_version VARCHAR(48) NOT NULL DEFAULT 'paper-shadow-decision-evidence.v1'
        CHECK (contract_version = 'paper-shadow-decision-evidence.v1'),
    symbol VARCHAR(20) NOT NULL,
    market_available_at TIMESTAMPTZ NOT NULL,
    news_available_at TIMESTAMPTZ NOT NULL,
    decision_at TIMESTAMPTZ NOT NULL,
    market_source_fingerprint CHAR(64) NOT NULL
        CHECK (market_source_fingerprint ~ '^[a-f0-9]{64}$'),
    news_source_fingerprint CHAR(64) NOT NULL
        CHECK (news_source_fingerprint ~ '^[a-f0-9]{64}$'),
    input_fingerprint CHAR(64) NOT NULL
        CHECK (input_fingerprint ~ '^[a-f0-9]{64}$'),
    case_fingerprint CHAR(64) NOT NULL UNIQUE
        CHECK (case_fingerprint ~ '^[a-f0-9]{64}$'),
    recorded_at TIMESTAMPTZ NOT NULL,
    recorded_by VARCHAR(120) NOT NULL,
    source_custody_independently_verified BOOLEAN NOT NULL DEFAULT FALSE
        CHECK (source_custody_independently_verified = FALSE),
    live_orders_allowed BOOLEAN NOT NULL DEFAULT FALSE CHECK (live_orders_allowed = FALSE),
    can_authorize_capital BOOLEAN NOT NULL DEFAULT FALSE CHECK (can_authorize_capital = FALSE),
    CONSTRAINT ck_paper_shadow_case_times CHECK (
        market_available_at <= decision_at AND news_available_at <= decision_at
    )
);

CREATE TABLE paper_shadow_model_decision (
    case_id UUID NOT NULL REFERENCES paper_shadow_decision_case(case_id),
    model_version_id UUID NOT NULL REFERENCES model_version(version_id),
    model_version_fingerprint CHAR(64) NOT NULL
        CHECK (model_version_fingerprint ~ '^[a-f0-9]{64}$'),
    role VARCHAR(24) NOT NULL CHECK (role IN ('PAPER_CHAMPION', 'SHADOW_CHALLENGER')),
    action VARCHAR(8) NOT NULL CHECK (action IN ('ENTER', 'WAIT', 'ABSTAIN')),
    predicted_at TIMESTAMPTZ NOT NULL,
    paper_order_id UUID REFERENCES paper_order(id),
    decision_fingerprint CHAR(64) NOT NULL
        CHECK (decision_fingerprint ~ '^[a-f0-9]{64}$'),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    capital_authority BOOLEAN NOT NULL DEFAULT FALSE CHECK (capital_authority = FALSE),
    PRIMARY KEY (case_id, model_version_id),
    CONSTRAINT ck_shadow_paper_order_link CHECK (
        paper_order_id IS NULL OR (role = 'PAPER_CHAMPION' AND action = 'ENTER')
    )
);

-- Exactly one champion and one evidence link per PAPER order; challengers never touch an order.
CREATE UNIQUE INDEX uq_paper_shadow_one_champion
    ON paper_shadow_model_decision(case_id) WHERE role = 'PAPER_CHAMPION';
CREATE UNIQUE INDEX uq_paper_shadow_one_order_link
    ON paper_shadow_model_decision(paper_order_id) WHERE paper_order_id IS NOT NULL;
CREATE INDEX ix_paper_shadow_case_recent
    ON paper_shadow_decision_case(recorded_at DESC, case_id DESC);
CREATE INDEX ix_paper_shadow_decision_version
    ON paper_shadow_model_decision(model_version_id, created_at DESC);

-- V4-02: immutable side-by-side candidate comparison evidence.
-- Comparisons may rank research evidence for human review but never promote models or authorize capital.
CREATE TABLE backtest_comparison (
    comparison_id UUID PRIMARY KEY,
    contract_version VARCHAR(40) NOT NULL DEFAULT 'backtest-comparison.v1'
        CHECK (contract_version = 'backtest-comparison.v1'),
    from_at TIMESTAMPTZ NOT NULL,
    to_at TIMESTAMPTZ NOT NULL,
    seed BIGINT NOT NULL CHECK (seed >= 0),
    dataset_binding_fingerprint CHAR(64) NOT NULL
        CHECK (dataset_binding_fingerprint ~ '^[a-f0-9]{64}$'),
    target_binding_fingerprint CHAR(64) NOT NULL
        CHECK (target_binding_fingerprint ~ '^[a-f0-9]{64}$'),
    fold_binding_fingerprint CHAR(64) NOT NULL
        CHECK (fold_binding_fingerprint ~ '^[a-f0-9]{64}$'),
    economic_cost_binding_fingerprint CHAR(64) NOT NULL
        CHECK (economic_cost_binding_fingerprint ~ '^[a-f0-9]{64}$'),
    comparison_fingerprint CHAR(64) NOT NULL UNIQUE
        CHECK (comparison_fingerprint ~ '^[a-f0-9]{64}$'),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by VARCHAR(120) NOT NULL,
    promotion_allowed BOOLEAN NOT NULL DEFAULT FALSE CHECK (promotion_allowed = FALSE),
    can_authorize_capital BOOLEAN NOT NULL DEFAULT FALSE CHECK (can_authorize_capital = FALSE),
    CHECK (from_at < to_at)
);

CREATE TABLE backtest_comparison_candidate (
    comparison_id UUID NOT NULL REFERENCES backtest_comparison(comparison_id),
    result_id UUID NOT NULL REFERENCES experiment_result(result_id),
    model_version_id UUID NOT NULL REFERENCES model_version(version_id),
    candidate_order SMALLINT NOT NULL CHECK (candidate_order BETWEEN 0 AND 19),
    assessment VARCHAR(20) NOT NULL CHECK (assessment IN ('PASS', 'FAIL', 'INCONCLUSIVE')),
    sample_count BIGINT NOT NULL CHECK (sample_count >= 0),
    metrics_fingerprint CHAR(64) NOT NULL
        CHECK (metrics_fingerprint ~ '^[a-f0-9]{64}$'),
    metrics_json JSONB NOT NULL,
    candidate_fingerprint CHAR(64) NOT NULL
        CHECK (candidate_fingerprint ~ '^[a-f0-9]{64}$'),
    PRIMARY KEY (comparison_id, result_id),
    CONSTRAINT uq_backtest_comparison_candidate_order UNIQUE (comparison_id, candidate_order),
    CONSTRAINT uq_backtest_comparison_candidate_fingerprint UNIQUE (comparison_id, candidate_fingerprint)
);

CREATE INDEX ix_backtest_comparison_created
    ON backtest_comparison(created_at DESC, comparison_id DESC);
CREATE INDEX ix_backtest_comparison_candidate_model
    ON backtest_comparison_candidate(model_version_id, comparison_id);

-- V4-02: versioned sample-sufficiency semantics for immutable backtest comparisons.
-- Insufficient evidence is derived as INCONCLUSIVE; callers cannot override effective assessment.
CREATE TABLE sample_sufficiency_evaluation (
    evaluation_id UUID PRIMARY KEY,
    contract_version VARCHAR(40) NOT NULL DEFAULT 'sample-sufficiency.v1'
        CHECK (contract_version = 'sample-sufficiency.v1'),
    comparison_id UUID NOT NULL REFERENCES backtest_comparison(comparison_id),
    comparison_fingerprint CHAR(64) NOT NULL
        CHECK (comparison_fingerprint ~ '^[a-f0-9]{64}$'),
    minimum_sample_count BIGINT NOT NULL CHECK (minimum_sample_count BETWEEN 1 AND 1000000000),
    policy_fingerprint CHAR(64) NOT NULL
        CHECK (policy_fingerprint ~ '^[a-f0-9]{64}$'),
    overall_status VARCHAR(20) NOT NULL CHECK (overall_status IN ('SUFFICIENT', 'INCONCLUSIVE')),
    evaluation_fingerprint CHAR(64) NOT NULL UNIQUE
        CHECK (evaluation_fingerprint ~ '^[a-f0-9]{64}$'),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by VARCHAR(120) NOT NULL,
    promotion_allowed BOOLEAN NOT NULL DEFAULT FALSE CHECK (promotion_allowed = FALSE),
    can_authorize_capital BOOLEAN NOT NULL DEFAULT FALSE CHECK (can_authorize_capital = FALSE),
    CONSTRAINT uq_sample_sufficiency_policy UNIQUE (comparison_id, policy_fingerprint)
);

CREATE TABLE sample_sufficiency_candidate (
    evaluation_id UUID NOT NULL REFERENCES sample_sufficiency_evaluation(evaluation_id),
    result_id UUID NOT NULL REFERENCES experiment_result(result_id),
    model_version_id UUID NOT NULL REFERENCES model_version(version_id),
    candidate_order SMALLINT NOT NULL CHECK (candidate_order BETWEEN 0 AND 19),
    observed_sample_count BIGINT NOT NULL CHECK (observed_sample_count >= 0),
    minimum_sample_count BIGINT NOT NULL CHECK (minimum_sample_count BETWEEN 1 AND 1000000000),
    original_assessment VARCHAR(20) NOT NULL CHECK (original_assessment IN ('PASS', 'FAIL', 'INCONCLUSIVE')),
    effective_assessment VARCHAR(20) NOT NULL CHECK (effective_assessment IN ('PASS', 'FAIL', 'INCONCLUSIVE')),
    sufficient BOOLEAN NOT NULL,
    shortfall BIGINT NOT NULL CHECK (shortfall >= 0),
    evidence_fingerprint CHAR(64) NOT NULL
        CHECK (evidence_fingerprint ~ '^[a-f0-9]{64}$'),
    PRIMARY KEY (evaluation_id, result_id),
    CONSTRAINT uq_sample_sufficiency_candidate_order UNIQUE (evaluation_id, candidate_order),
    CONSTRAINT ck_sample_sufficiency_candidate_semantics CHECK (
        (observed_sample_count < minimum_sample_count
            AND sufficient = FALSE
            AND shortfall = minimum_sample_count - observed_sample_count
            AND effective_assessment = 'INCONCLUSIVE')
        OR
        (observed_sample_count >= minimum_sample_count
            AND sufficient = TRUE
            AND shortfall = 0
            AND effective_assessment = original_assessment)
    )
);

CREATE INDEX ix_sample_sufficiency_comparison
    ON sample_sufficiency_evaluation(comparison_id, created_at DESC, evaluation_id DESC);
CREATE INDEX ix_sample_sufficiency_candidate_model
    ON sample_sufficiency_candidate(model_version_id, evaluation_id);

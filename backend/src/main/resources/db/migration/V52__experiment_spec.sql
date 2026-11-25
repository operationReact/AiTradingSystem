-- V4-02: immutable experiment specification and canonical causal-evidence bindings.
-- Execution/orchestration and results are deliberately separate later contracts.
CREATE TABLE experiment_spec (
    experiment_id UUID PRIMARY KEY,
    contract_version VARCHAR(32) NOT NULL DEFAULT 'experiment-spec.v1'
        CHECK (contract_version = 'experiment-spec.v1'),
    model_version_id UUID NOT NULL REFERENCES model_version(version_id),
    from_at TIMESTAMPTZ NOT NULL,
    to_at TIMESTAMPTZ NOT NULL,
    seed BIGINT NOT NULL CHECK (seed >= 0),
    dataset_binding JSONB NOT NULL,
    dataset_binding_fingerprint CHAR(64) NOT NULL
        CHECK (dataset_binding_fingerprint ~ '^[a-f0-9]{64}$'),
    target_binding JSONB NOT NULL,
    target_binding_fingerprint CHAR(64) NOT NULL
        CHECK (target_binding_fingerprint ~ '^[a-f0-9]{64}$'),
    fold_binding JSONB NOT NULL,
    fold_binding_fingerprint CHAR(64) NOT NULL
        CHECK (fold_binding_fingerprint ~ '^[a-f0-9]{64}$'),
    economic_cost_binding JSONB NOT NULL,
    economic_cost_binding_fingerprint CHAR(64) NOT NULL
        CHECK (economic_cost_binding_fingerprint ~ '^[a-f0-9]{64}$'),
    spec_fingerprint CHAR(64) NOT NULL UNIQUE
        CHECK (spec_fingerprint ~ '^[a-f0-9]{64}$'),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by VARCHAR(120) NOT NULL,
    CONSTRAINT ck_experiment_spec_window CHECK (from_at < to_at)
);

CREATE INDEX ix_experiment_spec_model_created
    ON experiment_spec(model_version_id, created_at DESC, experiment_id DESC);

-- V4-02: durable reproducible experiment orchestration and immutable result evidence.
-- Jobs/results are research evidence only and can never authorize capital.
CREATE TABLE experiment_job (
    job_id UUID PRIMARY KEY,
    contract_version VARCHAR(32) NOT NULL DEFAULT 'experiment-job.v1'
        CHECK (contract_version = 'experiment-job.v1'),
    experiment_id UUID NOT NULL REFERENCES experiment_spec(experiment_id),
    spec_fingerprint CHAR(64) NOT NULL
        CHECK (spec_fingerprint ~ '^[a-f0-9]{64}$'),
    runner_type VARCHAR(32) NOT NULL CHECK (runner_type IN ('JAVA_BACKTEST', 'PYTHON_INTELLIGENCE')),
    runner_version VARCHAR(120) NOT NULL,
    orchestration_fingerprint CHAR(64) NOT NULL UNIQUE
        CHECK (orchestration_fingerprint ~ '^[a-f0-9]{64}$'),
    status VARCHAR(20) NOT NULL DEFAULT 'QUEUED'
        CHECK (status IN ('QUEUED', 'CLAIMED', 'RUNNING', 'SUCCEEDED', 'FAILED', 'CANCELLED')),
    attempt INTEGER NOT NULL DEFAULT 0 CHECK (attempt >= 0),
    max_attempts INTEGER NOT NULL CHECK (max_attempts BETWEEN 1 AND 10),
    claim_token UUID,
    claimed_by VARCHAR(120),
    claimed_at TIMESTAMPTZ,
    lease_expires_at TIMESTAMPTZ,
    started_at TIMESTAMPTZ,
    finished_at TIMESTAMPTZ,
    failure_code VARCHAR(80),
    failure_message VARCHAR(512),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by VARCHAR(120) NOT NULL,
    can_authorize_capital BOOLEAN NOT NULL DEFAULT FALSE CHECK (can_authorize_capital = FALSE),
    CONSTRAINT ck_experiment_job_claim_fields CHECK (
        (status IN ('CLAIMED', 'RUNNING') AND claim_token IS NOT NULL AND claimed_by IS NOT NULL
            AND claimed_at IS NOT NULL AND lease_expires_at IS NOT NULL)
        OR status NOT IN ('CLAIMED', 'RUNNING')
    ),
    CONSTRAINT ck_experiment_job_terminal_finished CHECK (
        (status IN ('SUCCEEDED', 'FAILED', 'CANCELLED') AND finished_at IS NOT NULL)
        OR status NOT IN ('SUCCEEDED', 'FAILED', 'CANCELLED')
    )
);

CREATE INDEX ix_experiment_job_queue
    ON experiment_job(runner_type, status, created_at, job_id);
CREATE INDEX ix_experiment_job_experiment
    ON experiment_job(experiment_id, created_at DESC, job_id DESC);

CREATE TABLE experiment_result (
    result_id UUID PRIMARY KEY,
    contract_version VARCHAR(32) NOT NULL DEFAULT 'experiment-result.v1'
        CHECK (contract_version = 'experiment-result.v1'),
    job_id UUID NOT NULL UNIQUE REFERENCES experiment_job(job_id),
    experiment_id UUID NOT NULL REFERENCES experiment_spec(experiment_id),
    model_version_id UUID NOT NULL REFERENCES model_version(version_id),
    spec_fingerprint CHAR(64) NOT NULL
        CHECK (spec_fingerprint ~ '^[a-f0-9]{64}$'),
    orchestration_fingerprint CHAR(64) NOT NULL
        CHECK (orchestration_fingerprint ~ '^[a-f0-9]{64}$'),
    assessment VARCHAR(20) NOT NULL CHECK (assessment IN ('PASS', 'FAIL', 'INCONCLUSIVE')),
    sample_count BIGINT NOT NULL CHECK (sample_count >= 0),
    result_artifact_fingerprint CHAR(64) NOT NULL
        CHECK (result_artifact_fingerprint ~ '^[a-f0-9]{64}$'),
    metrics_fingerprint CHAR(64) NOT NULL
        CHECK (metrics_fingerprint ~ '^[a-f0-9]{64}$'),
    evidence_fingerprint CHAR(64) NOT NULL
        CHECK (evidence_fingerprint ~ '^[a-f0-9]{64}$'),
    result_fingerprint CHAR(64) NOT NULL UNIQUE
        CHECK (result_fingerprint ~ '^[a-f0-9]{64}$'),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by VARCHAR(120) NOT NULL,
    can_authorize_capital BOOLEAN NOT NULL DEFAULT FALSE CHECK (can_authorize_capital = FALSE)
);

CREATE INDEX ix_experiment_result_experiment
    ON experiment_result(experiment_id, created_at DESC, result_id DESC);
CREATE INDEX ix_experiment_result_model
    ON experiment_result(model_version_id, created_at DESC, result_id DESC);

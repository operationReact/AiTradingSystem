-- V4-09: durable, bounded, evidence-only inference attempt slots.
-- Each row belongs to one already-enrolled exact model version and one V59 DB
-- source capture. No row can authorize capital or trigger broker execution.
CREATE TABLE v4_matched_inference_job (
    job_id UUID PRIMARY KEY,
    session_id UUID NOT NULL,
    case_key VARCHAR(96) NOT NULL,
    model_version_id UUID NOT NULL,
    source_capture_fingerprint CHAR(64) NOT NULL
        CHECK (source_capture_fingerprint ~ '^[a-f0-9]{64}$'),
    model_version_fingerprint CHAR(64) NOT NULL
        CHECK (model_version_fingerprint ~ '^[a-f0-9]{64}$'),
    job_fingerprint CHAR(64) NOT NULL UNIQUE
        CHECK (job_fingerprint ~ '^[a-f0-9]{64}$'),
    status VARCHAR(16) NOT NULL DEFAULT 'QUEUED'
        CHECK (status IN ('QUEUED','CLAIMED','COMPLETED','FAILED','CANCELLED')),
    attempt INTEGER NOT NULL DEFAULT 0 CHECK (attempt BETWEEN 0 AND 3),
    claim_token UUID,
    claimed_by VARCHAR(80),
    claimed_at TIMESTAMPTZ,
    lease_expires_at TIMESTAMPTZ,
    predicted_at TIMESTAMPTZ,
    reported_at TIMESTAMPTZ,
    action VARCHAR(8) CHECK (action IN ('ENTER','WAIT','ABSTAIN')),
    inference_fingerprint CHAR(64)
        CHECK (inference_fingerprint IS NULL OR inference_fingerprint ~ '^[a-f0-9]{64}$'),
    outcome_fingerprint CHAR(64)
        CHECK (outcome_fingerprint IS NULL OR outcome_fingerprint ~ '^[a-f0-9]{64}$'),
    failure_code VARCHAR(64),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    can_authorize_capital BOOLEAN NOT NULL DEFAULT FALSE
        CHECK (can_authorize_capital = FALSE),
    CONSTRAINT uq_v4_inference_case_model UNIQUE (session_id, case_key, model_version_id),
    CONSTRAINT fk_v4_inference_source FOREIGN KEY (session_id, case_key)
        REFERENCES v4_matched_source_capture(session_id, case_key),
    CONSTRAINT fk_v4_inference_participant FOREIGN KEY (session_id, model_version_id)
        REFERENCES v4_matched_participant(session_id, model_version_id),
    CONSTRAINT ck_v4_inference_lease CHECK (
        (status = 'CLAIMED' AND claim_token IS NOT NULL
            AND claimed_by IS NOT NULL AND claimed_at IS NOT NULL AND lease_expires_at IS NOT NULL)
        OR status <> 'CLAIMED'
    ),
    CONSTRAINT ck_v4_inference_completion CHECK (
        (status = 'COMPLETED' AND action IS NOT NULL AND predicted_at IS NOT NULL
            AND reported_at IS NOT NULL AND inference_fingerprint IS NOT NULL
            AND outcome_fingerprint IS NOT NULL)
        OR (status <> 'COMPLETED' AND action IS NULL AND
            inference_fingerprint IS NULL AND outcome_fingerprint IS NULL)
    )
);

CREATE INDEX ix_v4_inference_claim
    ON v4_matched_inference_job(status, lease_expires_at, created_at, job_id);
CREATE INDEX ix_v4_inference_case
    ON v4_matched_inference_job(session_id, case_key, model_version_id);

-- V4-09 / V61: durable *intent before transport* for one PAPER/SHADOW
-- registered model and one causally frozen V60 case. Not an execution queue.
-- An absent terminal receipt after process interruption is UNKNOWN, NOT retryable.
CREATE TABLE v4_research_inference_reservation (
    reservation_id UUID PRIMARY KEY,
    session_id UUID NOT NULL,
    case_key VARCHAR(96) NOT NULL,
    model_version_id UUID NOT NULL,
    enrollment_fingerprint CHAR(64) NOT NULL
        CHECK (enrollment_fingerprint ~ '^[a-f0-9]{64}$'),
    model_version_fingerprint CHAR(64) NOT NULL
        CHECK (model_version_fingerprint ~ '^[a-f0-9]{64}$'),
    artifact_fingerprint CHAR(64) NOT NULL
        CHECK (artifact_fingerprint ~ '^[a-f0-9]{64}$'),
    source_input_fingerprint CHAR(64) NOT NULL
        CHECK (source_input_fingerprint ~ '^[a-f0-9]{64}$'),
    feature_payload_fingerprint CHAR(64) NOT NULL
        CHECK (feature_payload_fingerprint ~ '^[a-f0-9]{64}$'),
    feature_as_of TIMESTAMPTZ NOT NULL,
    reserved_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    reserved_by VARCHAR(120) NOT NULL CHECK (length(trim(reserved_by)) > 0),
    can_authorize_capital BOOLEAN NOT NULL DEFAULT FALSE
        CHECK (can_authorize_capital = FALSE),
    UNIQUE (session_id, case_key, model_version_id),
    FOREIGN KEY (session_id, case_key)
        REFERENCES v4_matched_feature_projection(session_id, case_key),
    FOREIGN KEY (session_id, model_version_id)
        REFERENCES v4_matched_participant(session_id, model_version_id),
    CHECK (expires_at > reserved_at AND expires_at <= reserved_at + INTERVAL '2 minutes'),
    CHECK (feature_as_of <= reserved_at)
);

-- One terminal receipt only; no status transitions or mutable retry counters.
-- SUCCEEDED means a validated research response, NOT paper/backtest acceptance.
CREATE TABLE v4_research_inference_receipt (
    reservation_id UUID PRIMARY KEY
        REFERENCES v4_research_inference_reservation(reservation_id),
    status VARCHAR(12) NOT NULL
        CHECK (status IN ('SUCCEEDED','REJECTED','TIMEOUT','UNKNOWN')),
    reason_code VARCHAR(40) NOT NULL CHECK (reason_code IN (
        'VALIDATED_RESEARCH_RESPONSE','RESPONSE_REJECTED','TRANSPORT_TIMEOUT',
        'TRANSPORT_UNKNOWN','SESSION_REVOKED','EVIDENCE_EXPIRED',
        'OPERATOR_MARKED_UNKNOWN')),
    request_fingerprint CHAR(64)
        CHECK (request_fingerprint IS NULL OR request_fingerprint ~ '^[a-f0-9]{64}$'),
    response_fingerprint CHAR(64)
        CHECK (response_fingerprint IS NULL OR response_fingerprint ~ '^[a-f0-9]{64}$'),
    model_family VARCHAR(20)
        CHECK (model_family IS NULL OR model_family IN ('CATBOOST','LIGHTGBM')),
    probability NUMERIC(12,10) CHECK (probability BETWEEN 0 AND 1),
    action VARCHAR(8)
        CHECK (action IS NULL OR action IN ('ENTER','WAIT','ABSTAIN')),
    evaluated_at TIMESTAMPTZ,
    recorded_at TIMESTAMPTZ NOT NULL,
    recorded_by VARCHAR(120) NOT NULL CHECK (length(trim(recorded_by)) > 0),
    can_authorize_capital BOOLEAN NOT NULL DEFAULT FALSE
        CHECK (can_authorize_capital = FALSE),
    CONSTRAINT ck_v4_inference_receipt_shape CHECK (
        (status = 'SUCCEEDED'
            AND reason_code = 'VALIDATED_RESEARCH_RESPONSE'
            AND request_fingerprint IS NOT NULL AND response_fingerprint IS NOT NULL
            AND model_family IS NOT NULL AND probability IS NOT NULL
            AND action IS NOT NULL AND evaluated_at IS NOT NULL)
        OR (status <> 'SUCCEEDED'
            AND reason_code <> 'VALIDATED_RESEARCH_RESPONSE'
            AND response_fingerprint IS NULL AND model_family IS NULL
            AND probability IS NULL AND action IS NULL AND evaluated_at IS NULL)
    ),
    CONSTRAINT ck_v4_inference_reason_status CHECK (
        (status = 'REJECTED' AND reason_code IN (
            'RESPONSE_REJECTED','SESSION_REVOKED','EVIDENCE_EXPIRED'))
        OR (status = 'TIMEOUT' AND reason_code = 'TRANSPORT_TIMEOUT')
        OR (status = 'UNKNOWN' AND reason_code IN (
            'TRANSPORT_UNKNOWN','OPERATOR_MARKED_UNKNOWN'))
        OR (status = 'SUCCEEDED' AND reason_code = 'VALIDATED_RESEARCH_RESPONSE')
    )
);

CREATE INDEX ix_v4_research_inference_session_case
    ON v4_research_inference_reservation(session_id, case_key, reserved_at DESC);
CREATE INDEX ix_v4_research_inference_unresolved
    ON v4_research_inference_reservation(expires_at, reservation_id);

-- A database-side guard, not just absence of a mutation REST endpoint.
CREATE FUNCTION v4_reject_research_inference_mutation()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'V4 research inference evidence is append-only';
END;
$$;

CREATE TRIGGER trg_v4_research_reservation_immutable
BEFORE UPDATE OR DELETE ON v4_research_inference_reservation
FOR EACH ROW EXECUTE FUNCTION v4_reject_research_inference_mutation();

CREATE TRIGGER trg_v4_research_receipt_immutable
BEFORE UPDATE OR DELETE ON v4_research_inference_receipt
FOR EACH ROW EXECUTE FUNCTION v4_reject_research_inference_mutation();

-- No automatic resubmission is derived from absent/expired receipts;
-- a separate orchestrator must reconcile/resume as UNKNOWN without replay.

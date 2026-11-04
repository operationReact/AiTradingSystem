-- V4-09 / V62: immutable custody link from a *complete* set of validated
-- V61 research model receipts to an original V56 PAPER/SHADOW matched case.
-- This does NOT attest licensed source rights, independent performance or LIVE.
CREATE TABLE v4_research_matched_case_link (
    session_id UUID NOT NULL,
    case_key VARCHAR(96) NOT NULL,
    case_fingerprint CHAR(64) NOT NULL
        CHECK (case_fingerprint ~ '^[a-f0-9]{64}$'),
    enrollment_fingerprint CHAR(64) NOT NULL
        CHECK (enrollment_fingerprint ~ '^[a-f0-9]{64}$'),
    source_input_fingerprint CHAR(64) NOT NULL
        CHECK (source_input_fingerprint ~ '^[a-f0-9]{64}$'),
    feature_payload_fingerprint CHAR(64) NOT NULL
        CHECK (feature_payload_fingerprint ~ '^[a-f0-9]{64}$'),
    receipt_roster_fingerprint CHAR(64) NOT NULL
        CHECK (receipt_roster_fingerprint ~ '^[a-f0-9]{64}$'),
    model_count INTEGER NOT NULL CHECK (model_count BETWEEN 2 AND 10),
    recorded_at TIMESTAMPTZ NOT NULL,
    recorded_by VARCHAR(120) NOT NULL CHECK (length(trim(recorded_by)) > 0),
    can_authorize_capital BOOLEAN NOT NULL DEFAULT FALSE
        CHECK (can_authorize_capital = FALSE),
    PRIMARY KEY (session_id, case_key),
    FOREIGN KEY (session_id, case_key)
        REFERENCES v4_matched_case(session_id, case_key)
);

CREATE INDEX ix_v4_research_matched_link_recent
    ON v4_research_matched_case_link(session_id, recorded_at DESC, case_key);

-- Every link is insert-once; caller performs V56 append plus V62 insertion
-- inside one database transaction. A failed link insert rolls back the case.
CREATE TRIGGER trg_v4_research_matched_case_link_immutable
BEFORE UPDATE OR DELETE ON v4_research_matched_case_link
FOR EACH ROW EXECUTE FUNCTION v4_reject_research_inference_mutation();

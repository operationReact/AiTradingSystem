-- V4-09: optional append-only link from canonical V56 model evidence to an
-- ALREADY EXISTING automated PAPER order. Never creates an order, model prediction
-- or fill, and cannot grant LIVE or capital authority.
CREATE TABLE v4_matched_paper_order_link (
    session_id UUID NOT NULL,
    case_key VARCHAR(96) NOT NULL,
    model_version_id UUID NOT NULL,
    paper_order_id UUID NOT NULL UNIQUE REFERENCES paper_order(id),
    decision_fingerprint CHAR(64) NOT NULL CHECK (decision_fingerprint ~ '^[a-f0-9]{64}$'),
    link_fingerprint CHAR(64) NOT NULL UNIQUE CHECK (link_fingerprint ~ '^[a-f0-9]{64}$'),
    linked_at TIMESTAMPTZ NOT NULL,
    linked_by VARCHAR(120) NOT NULL,
    can_authorize_capital BOOLEAN NOT NULL DEFAULT FALSE CHECK (can_authorize_capital = FALSE),
    PRIMARY KEY (session_id, case_key),
    CONSTRAINT fk_v4_matched_link_decision FOREIGN KEY (session_id, case_key, model_version_id)
        REFERENCES v4_matched_decision(session_id, case_key, model_version_id)
);
CREATE INDEX ix_v4_matched_paper_link_recent
    ON v4_matched_paper_order_link(linked_at DESC, session_id, case_key);

-- V4-09: authoritative session-level safety gate, distinct from immutable enrollment/decisions.
-- Existing sessions start ACTIVE; this does NOT attest runtime source authenticity.
CREATE TABLE v4_matched_session_state (
    session_id UUID PRIMARY KEY REFERENCES v4_matched_session(session_id),
    state VARCHAR(16) NOT NULL CHECK (state IN ('ACTIVE','PAUSED','QUARANTINED')),
    revision BIGINT NOT NULL CHECK (revision >= 0),
    state_fingerprint CHAR(64) NOT NULL CHECK (state_fingerprint ~ '^[a-f0-9]{64}$'),
    reason VARCHAR(512) NOT NULL CHECK (length(trim(reason)) >= 3),
    updated_at TIMESTAMPTZ NOT NULL,
    updated_by VARCHAR(120) NOT NULL,
    can_authorize_capital BOOLEAN NOT NULL DEFAULT FALSE CHECK (can_authorize_capital = FALSE)
);

CREATE TABLE v4_matched_session_transition (
    session_id UUID NOT NULL REFERENCES v4_matched_session(session_id),
    revision BIGINT NOT NULL CHECK (revision >= 0),
    previous_state VARCHAR(16) CHECK (previous_state IN ('ACTIVE','PAUSED','QUARANTINED')),
    new_state VARCHAR(16) NOT NULL CHECK (new_state IN ('ACTIVE','PAUSED','QUARANTINED')),
    reason VARCHAR(512) NOT NULL CHECK (length(trim(reason)) >= 3),
    state_fingerprint CHAR(64) NOT NULL CHECK (state_fingerprint ~ '^[a-f0-9]{64}$'),
    previous_fingerprint CHAR(64) CHECK (
        previous_fingerprint IS NULL OR previous_fingerprint ~ '^[a-f0-9]{64}$'),
    changed_at TIMESTAMPTZ NOT NULL,
    changed_by VARCHAR(120) NOT NULL,
    can_authorize_capital BOOLEAN NOT NULL DEFAULT FALSE CHECK (can_authorize_capital = FALSE),
    PRIMARY KEY (session_id, revision),
    CONSTRAINT ck_matched_transition_initial CHECK (
        (revision = 0 AND previous_state IS NULL AND previous_fingerprint IS NULL
            AND new_state = 'ACTIVE')
        OR (revision > 0 AND previous_state IS NOT NULL AND previous_fingerprint IS NOT NULL)),
    CONSTRAINT ck_matched_transition_legal CHECK (
        revision = 0
        OR (previous_state = 'ACTIVE' AND new_state IN ('PAUSED','QUARANTINED'))
        OR (previous_state = 'PAUSED' AND new_state IN ('ACTIVE','QUARANTINED')))
);

CREATE INDEX ix_v4_matched_session_state_updated
    ON v4_matched_session_state(state, updated_at DESC, session_id);

-- Backfill existing sessions preserving their original author/time. Revision zero
-- deliberately reuses the enrollment fingerprint rather than inventing runtime evidence.
INSERT INTO v4_matched_session_state (
    session_id, state, revision, state_fingerprint, reason, updated_at, updated_by)
SELECT session_id, 'ACTIVE', 0, enrollment_fingerprint,
       'Initial enrollment', created_at, created_by
  FROM v4_matched_session;

INSERT INTO v4_matched_session_transition (
    session_id, revision, previous_state, new_state, reason,
    state_fingerprint, previous_fingerprint, changed_at, changed_by)
SELECT session_id, revision, NULL, state, reason, state_fingerprint, NULL,
       updated_at, updated_by
  FROM v4_matched_session_state;

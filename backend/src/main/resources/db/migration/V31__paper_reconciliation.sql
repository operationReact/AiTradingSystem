-- Phase 6 / P06-W05: durable, non-destructive PAPER reconciliation evidence.

CREATE TABLE paper_reconciliation_run (
    id UUID PRIMARY KEY,
    status VARCHAR(16) NOT NULL CHECK (status IN ('RUNNING','CLEAN','DIFFERENCES','FAILED')),
    started_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ,
    checks_performed INTEGER NOT NULL DEFAULT 0 CHECK (checks_performed >= 0),
    difference_count INTEGER NOT NULL DEFAULT 0 CHECK (difference_count >= 0),
    error_code VARCHAR(80),
    error_message VARCHAR(500)
);

CREATE TABLE paper_reconciliation_difference (
    id BIGSERIAL PRIMARY KEY,
    run_id UUID NOT NULL REFERENCES paper_reconciliation_run(id),
    entity_type VARCHAR(40) NOT NULL,
    entity_id VARCHAR(120) NOT NULL,
    difference_code VARCHAR(80) NOT NULL,
    expected JSONB NOT NULL,
    actual JSONB NOT NULL,
    severity VARCHAR(12) NOT NULL CHECK (severity IN ('INFO','WARNING','ERROR','CRITICAL')),
    resolved BOOLEAN NOT NULL DEFAULT FALSE,
    resolution_audit_event_id BIGINT REFERENCES audit_event(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    resolved_at TIMESTAMPTZ,
    CONSTRAINT ck_paper_reconciliation_resolution
        CHECK ((resolved = FALSE AND resolved_at IS NULL)
            OR (resolved = TRUE AND resolved_at IS NOT NULL AND resolution_audit_event_id IS NOT NULL))
);

CREATE INDEX idx_paper_reconciliation_run_started
    ON paper_reconciliation_run (started_at DESC);
CREATE INDEX idx_paper_reconciliation_difference_run
    ON paper_reconciliation_difference (run_id, id);
CREATE INDEX idx_paper_reconciliation_difference_unresolved
    ON paper_reconciliation_difference (severity, created_at DESC)
    WHERE resolved = FALSE;

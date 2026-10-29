CREATE TABLE research_scan_diagnostic (
    id BIGSERIAL PRIMARY KEY,
    scan_run_id UUID NOT NULL REFERENCES research_scan_run(id) ON DELETE CASCADE,
    instrument_id BIGINT NULL,
    strategy VARCHAR(80) NULL,
    severity VARCHAR(12) NOT NULL,
    code VARCHAR(80) NOT NULL,
    message VARCHAR(300) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX ix_research_scan_diagnostic_run_created
    ON research_scan_diagnostic (scan_run_id, created_at, id);

ALTER TABLE research_scan_diagnostic
    ADD CONSTRAINT ck_research_scan_diagnostic_severity
    CHECK (severity IN ('INFO', 'WARN', 'ERROR'));

-- Persisted per-run, per-instrument, per-strategy research outcomes (issue #998).
-- Candidates alone cannot show that a strategy ran and found no signal, or that it was
-- never evaluated. This table records each of those explicitly. Rows are append-only:
-- UPDATE and DELETE are rejected by trigger. There is no foreign key to research_scan_run,
-- so run-level cleanup cannot silently remove or be blocked by evidence rows.
CREATE TABLE research_scan_outcome (
    scan_run_id UUID NOT NULL,
    instrument_id BIGINT NOT NULL,
    strategy VARCHAR(64) NOT NULL,
    outcome VARCHAR(16) NOT NULL
      CHECK (outcome IN ('CANDIDATE','NO_SIGNAL','NOT_EVALUATED')),
    reason_code VARCHAR(80) NOT NULL,
    data_cutoff_at TIMESTAMPTZ,
    recorded_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (scan_run_id, instrument_id, strategy),
    CONSTRAINT ck_research_scan_outcome_candidate
      CHECK (outcome <> 'CANDIDATE' OR data_cutoff_at IS NOT NULL)
);

CREATE INDEX ix_research_scan_outcome_run ON research_scan_outcome (scan_run_id, outcome);

CREATE FUNCTION research_scan_outcome_append_only() RETURNS trigger
    LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'research_scan_outcome is append-only';
END;
$$;

CREATE TRIGGER trg_research_scan_outcome_append_only
    BEFORE UPDATE OR DELETE ON research_scan_outcome
    FOR EACH ROW EXECUTE FUNCTION research_scan_outcome_append_only();

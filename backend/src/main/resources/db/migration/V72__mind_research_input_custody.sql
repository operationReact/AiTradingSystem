-- MIND-04A: one-way custody of the exact candle inputs used at scan time.
-- A digest proves later equality to this captured row, NOT independent provider
-- attestation or profitable strategy performance.
CREATE TABLE mind_research_input_custody (
    scan_run_id UUID NOT NULL REFERENCES research_scan_run(id),
    candidate_fingerprint CHAR(64) NOT NULL CHECK (candidate_fingerprint ~ '^[a-f0-9]{64}$'),
    strategy_code VARCHAR(80) NOT NULL CHECK (strategy_code = 'MA_RECLAIM_V1'),
    instrument_id BIGINT NOT NULL CHECK (instrument_id > 0),
    catalogue_version UUID NOT NULL,
    candle_count SMALLINT NOT NULL CHECK (candle_count = 21),
    input_first_at TIMESTAMPTZ NOT NULL,
    input_last_at TIMESTAMPTZ NOT NULL,
    max_fetched_at TIMESTAMPTZ NOT NULL,
    source_digest CHAR(64) NOT NULL CHECK (source_digest ~ '^[a-f0-9]{64}$'),
    canonical_input TEXT NOT NULL,
    captured_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (scan_run_id, candidate_fingerprint),
    CONSTRAINT mind_custody_input_times CHECK (
        input_first_at < input_last_at AND
        input_last_at <= captured_at AND
        max_fetched_at <= captured_at
    )
);
CREATE INDEX idx_mind_custody_captured ON mind_research_input_custody (captured_at DESC);
COMMENT ON TABLE mind_research_input_custody IS
  'Append-only frozen input custody for a research candidate. Not independently verified market-feed or trading authority.';

-- No ordinary service call may rewrite or delete a captured input bundle.
CREATE OR REPLACE FUNCTION reject_mind_custody_mutation()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Mintick Mind captured inputs are append-only';
END;
$$;
CREATE TRIGGER mind_custody_append_only
BEFORE UPDATE OR DELETE ON mind_research_input_custody
FOR EACH ROW EXECUTE FUNCTION reject_mind_custody_mutation();

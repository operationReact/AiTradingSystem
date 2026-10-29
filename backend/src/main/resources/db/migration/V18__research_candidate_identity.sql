ALTER TABLE research_scan_candidate
    ADD COLUMN candidate_fingerprint CHAR(64),
    ADD COLUMN strategy_config_version INTEGER,
    ADD COLUMN catalogue_version UUID,
    ADD COLUMN data_cutoff_at TIMESTAMPTZ;

DO $$
DECLARE
    constraint_name TEXT;
BEGIN
    SELECT c.conname
      INTO constraint_name
      FROM pg_constraint c
     WHERE c.conrelid = 'research_scan_candidate'::regclass
       AND c.contype = 'u'
       AND pg_get_constraintdef(c.oid) = 'UNIQUE (scan_run_id, instrument_id, strategy, signal_candle_time)'
     LIMIT 1;

    IF constraint_name IS NOT NULL THEN
        EXECUTE format('ALTER TABLE research_scan_candidate DROP CONSTRAINT %I', constraint_name);
    END IF;
END $$;

ALTER TABLE research_scan_candidate
    ADD CONSTRAINT ck_research_candidate_fingerprint
        CHECK (candidate_fingerprint IS NULL OR candidate_fingerprint ~ '^[0-9a-f]{64}$'),
    ADD CONSTRAINT ck_research_candidate_config_version
        CHECK (strategy_config_version IS NULL OR strategy_config_version > 0);

CREATE UNIQUE INDEX ux_research_candidate_run_fingerprint
    ON research_scan_candidate (scan_run_id, candidate_fingerprint)
    WHERE candidate_fingerprint IS NOT NULL;

CREATE INDEX ix_research_candidate_catalogue_version
    ON research_scan_candidate (catalogue_version)
    WHERE catalogue_version IS NOT NULL;

-- MIND-04C2B: append-only prospective outcome evidence linked to the
-- precommitted Phase 4C2A research candidate. NO broker order is placed.
CREATE TABLE mind_prospective_outcome (
    cohort_id UUID NOT NULL,
    member_slot SMALLINT NOT NULL CHECK (member_slot BETWEEN 1 AND 20),
    signal_candle_at TIMESTAMPTZ NOT NULL,
    entry_candle_at TIMESTAMPTZ NOT NULL,
    exit_candle_at TIMESTAMPTZ NOT NULL,
    evaluated_at TIMESTAMPTZ NOT NULL,
    candidate_fingerprint CHAR(64) NOT NULL CHECK (candidate_fingerprint ~ '^[a-f0-9]{64}$'),
    signal_source_digest CHAR(64) NOT NULL CHECK (signal_source_digest ~ '^[a-f0-9]{64}$'),
    outcome_source_digest CHAR(64) NOT NULL CHECK (outcome_source_digest ~ '^[a-f0-9]{64}$'),
    raw_entry NUMERIC(20,4) NOT NULL CHECK (raw_entry > 0),
    raw_exit NUMERIC(20,4) NOT NULL CHECK (raw_exit > 0),
    gross_return_percent NUMERIC(18,4) NOT NULL,
    illustrative_net_return_percent NUMERIC(18,4) NOT NULL,
    exit_reason VARCHAR(24) NOT NULL CHECK (exit_reason IN ('STOP', 'TARGET', 'SESSION_CLOSE')),
    source_bar_count SMALLINT NOT NULL CHECK (source_bar_count BETWEEN 1 AND 75),
    PRIMARY KEY (cohort_id, member_slot, signal_candle_at),
    CONSTRAINT mind_outcome_original_signal_fk FOREIGN KEY
        (cohort_id, member_slot, signal_candle_at)
        REFERENCES mind_prospective_signal_receipt(cohort_id, member_slot, signal_candle_at),
    CONSTRAINT mind_outcome_time_order CHECK (
        entry_candle_at = signal_candle_at + INTERVAL '5 minutes'
        AND exit_candle_at >= entry_candle_at
        AND evaluated_at >= exit_candle_at + INTERVAL '5 minutes'
    )
);
CREATE INDEX idx_mind_prospective_outcome_cohort
    ON mind_prospective_outcome(cohort_id, evaluated_at DESC);
CREATE TRIGGER mind_prospective_outcome_immutable
BEFORE UPDATE OR DELETE ON mind_prospective_outcome
FOR EACH ROW EXECUTE FUNCTION reject_mind_prospective_mutation();
COMMENT ON TABLE mind_prospective_outcome IS
  'Immutable prospective research outcomes; cost assumptions 10 bps fee and 5 bps slippage PER SIDE are illustrative, not actual Indian charges; no order authority.';

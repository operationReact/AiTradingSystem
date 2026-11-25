-- MIND-04C2A: immutable prospective scanner observations.
-- Capture must occur promptly after the five-minute bar CLOSE. A stored
-- research candidate is NOT a trade, an order, or a verified profitable edge.
CREATE TABLE mind_prospective_signal_receipt (
    cohort_id UUID NOT NULL REFERENCES mind_prospective_cohort(id),
    member_slot SMALLINT NOT NULL CHECK (member_slot BETWEEN 1 AND 20),
    signal_candle_at TIMESTAMPTZ NOT NULL,
    signal_bar_closed_at TIMESTAMPTZ NOT NULL,
    recorded_at TIMESTAMPTZ NOT NULL,
    source_digest CHAR(64) NOT NULL CHECK (source_digest ~ '^[a-f0-9]{64}$'),
    strategy_code VARCHAR(80) NOT NULL CHECK (strategy_code = 'MA_RECLAIM_V1'),
    strategy_version VARCHAR(20) NOT NULL CHECK (strategy_version = '1'),
    status VARCHAR(36) NOT NULL CHECK (status IN ('NO_SIGNAL', 'RESEARCH_CANDIDATE')),
    candidate_fingerprint CHAR(64) NULL CHECK (
       candidate_fingerprint IS NULL OR candidate_fingerprint ~ '^[a-f0-9]{64}$'),
    reference_price NUMERIC(20,4) NULL,
    suggested_stop NUMERIC(20,4) NULL,
    suggested_target NUMERIC(20,4) NULL,
    PRIMARY KEY(cohort_id, member_slot, signal_candle_at),
    CONSTRAINT mind_prospective_receipt_time CHECK (
      signal_bar_closed_at = signal_candle_at + INTERVAL '5 minutes'
      AND recorded_at >= signal_bar_closed_at
      AND recorded_at < signal_bar_closed_at + INTERVAL '10 minutes'
    ),
    CONSTRAINT mind_prospective_signal_metadata CHECK (
       (status = 'NO_SIGNAL' AND candidate_fingerprint IS NULL
         AND reference_price IS NULL AND suggested_stop IS NULL
         AND suggested_target IS NULL)
       OR
       (status = 'RESEARCH_CANDIDATE' AND candidate_fingerprint IS NOT NULL
         AND reference_price > 0 AND suggested_stop > 0 AND suggested_target > 0
         AND suggested_stop < reference_price
         AND suggested_target > reference_price)
    ),
    CONSTRAINT mind_prospective_signal_member_fk FOREIGN KEY(cohort_id, member_slot)
        REFERENCES mind_prospective_member(cohort_id, slot)
);

CREATE INDEX idx_mind_prospective_signals_time
    ON mind_prospective_signal_receipt(cohort_id, signal_candle_at DESC);

CREATE TRIGGER mind_prospective_signal_append_only
BEFORE UPDATE OR DELETE ON mind_prospective_signal_receipt
FOR EACH ROW EXECUTE FUNCTION reject_mind_prospective_mutation();

COMMENT ON TABLE mind_prospective_signal_receipt IS
  'Append-only scan-time MA reclaim observations. No outcomes, user capital or order authority.';

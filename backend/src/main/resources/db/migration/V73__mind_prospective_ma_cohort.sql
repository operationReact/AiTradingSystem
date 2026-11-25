-- MIND-04C1: Register cash-equity research membership BEFORE observing outcomes.
-- These tables freeze a deterministic cohort; they do not authorize trades,
-- claim unbiased historical P&L or attest to the exchange's original data.
CREATE TABLE mind_prospective_cohort (
    id UUID PRIMARY KEY,
    cohort_code VARCHAR(100) NOT NULL UNIQUE,
    protocol_version VARCHAR(40) NOT NULL,
    strategy_code VARCHAR(80) NOT NULL
        CHECK (strategy_code = 'MA_RECLAIM_V1'),
    catalogue_version UUID NOT NULL,
    catalogue_activated_at TIMESTAMPTZ NOT NULL,
    registered_at TIMESTAMPTZ NOT NULL,
    observation_starts_at TIMESTAMPTZ NOT NULL,
    target_sessions SMALLINT NOT NULL CHECK (target_sessions = 10),
    member_count SMALLINT NOT NULL CHECK (member_count = 20),
    selection_method VARCHAR(100) NOT NULL
        CHECK (selection_method = 'DETERMINISTIC_CATALOGUE_HASH_NO_SIGNAL_RANKING'),
    CONSTRAINT mind_prospective_forward_only
        CHECK (catalogue_activated_at <= registered_at
           AND registered_at < observation_starts_at)
);

CREATE TABLE mind_prospective_member (
    cohort_id UUID NOT NULL REFERENCES mind_prospective_cohort(id),
    slot SMALLINT NOT NULL CHECK (slot BETWEEN 1 AND 20),
    instrument_id BIGINT NOT NULL CHECK (instrument_id > 0),
    catalogue_version UUID NOT NULL,
    exchange VARCHAR(8) NOT NULL CHECK (exchange = 'NSE'),
    trading_symbol VARCHAR(150) NOT NULL
        CHECK (trading_symbol ~ '^[A-Z0-9._&-]+-EQ$'),
    symbol_token VARCHAR(80) NOT NULL CHECK (length(symbol_token) > 0),
    PRIMARY KEY(cohort_id, slot),
    UNIQUE(cohort_id, instrument_id),
    UNIQUE(cohort_id, trading_symbol)
);

CREATE INDEX idx_mind_prospective_member_instrument
    ON mind_prospective_member (instrument_id, catalogue_version);

-- Appends only: no API-driven change to cohort definition or membership.
CREATE OR REPLACE FUNCTION reject_mind_prospective_mutation()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Prospective research cohort is immutable';
END;
$$;
CREATE TRIGGER mind_prospective_cohort_immutable
    BEFORE UPDATE OR DELETE ON mind_prospective_cohort
    FOR EACH ROW EXECUTE FUNCTION reject_mind_prospective_mutation();
CREATE TRIGGER mind_prospective_member_immutable
    BEFORE UPDATE OR DELETE ON mind_prospective_member
    FOR EACH ROW EXECUTE FUNCTION reject_mind_prospective_mutation();

COMMENT ON TABLE mind_prospective_cohort IS
    'Admin registered forward-only MA research cohort, not a trading authorization.';
COMMENT ON TABLE mind_prospective_member IS
    'Immutable NSE cash-equity members selected without a strategy-score ranking.';

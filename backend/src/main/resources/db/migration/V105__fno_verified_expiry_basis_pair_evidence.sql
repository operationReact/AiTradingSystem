-- STR-180: EMPTY source and rights-attested as-of cash/future near-cash-close
-- paired quote custody. Quotes are NOT futures settlement prices or guaranteed
-- fill prices. No assumptions, rights, account funding, or records are seeded.
CREATE TABLE fno_verified_expiry_basis_pair_evidence (
    evidence_id BIGSERIAL PRIMARY KEY,
    provider_code VARCHAR(32) NOT NULL CHECK (
        provider_code IN ('UPSTOX_ANALYTICS', 'ANGEL_ONE_SMARTAPI')),
    spot_key VARCHAR(80) NOT NULL CHECK (
        spot_key ~ '^NSE_EQ\|[A-Za-z0-9._-]{1,64}$'),
    futures_key VARCHAR(80) NOT NULL CHECK (
        futures_key ~ '^NSE_FO\|[A-Za-z0-9._-]{1,64}$'),
    listed_from DATE NOT NULL,
    expiry DATE NOT NULL,
    lot_size INTEGER NOT NULL CHECK (lot_size BETWEEN 1 AND 100000),
    session_date DATE NOT NULL,
    spot_bid NUMERIC(20,6) NOT NULL CHECK (spot_bid > 0),
    spot_ask NUMERIC(20,6) NOT NULL CHECK (spot_ask > 0),
    futures_bid NUMERIC(20,6) NOT NULL CHECK (futures_bid > 0),
    futures_ask NUMERIC(20,6) NOT NULL CHECK (futures_ask > 0),
    spot_exchange_at TIMESTAMPTZ NOT NULL,
    futures_exchange_at TIMESTAMPTZ NOT NULL,
    spot_quote_first_observed_at TIMESTAMPTZ NOT NULL,
    futures_quote_first_observed_at TIMESTAMPTZ NOT NULL,
    spot_final_first_available_at TIMESTAMPTZ NOT NULL,
    futures_final_first_available_at TIMESTAMPTZ NOT NULL,
    financing_annual_bps NUMERIC(18,6) NOT NULL CHECK (
        financing_annual_bps BETWEEN 0 AND 5000),
    dividend_annual_bps NUMERIC(18,6) NOT NULL CHECK (
        dividend_annual_bps BETWEEN 0 AND 10000),
    round_trip_cost_bps NUMERIC(18,6) NOT NULL CHECK (
        round_trip_cost_bps BETWEEN 1 AND 2000),
    min_converging_steps INTEGER NOT NULL CHECK (min_converging_steps BETWEEN 3 AND 4),
    min_convergence_bps INTEGER NOT NULL CHECK (min_convergence_bps BETWEEN 1 AND 5000),
    min_after_cost_residual_bps INTEGER NOT NULL CHECK (
        min_after_cost_residual_bps BETWEEN 1 AND 3000),
    max_spread_bps INTEGER NOT NULL CHECK (max_spread_bps BETWEEN 1 AND 500),
    max_session_move_bps INTEGER NOT NULL CHECK (
        max_session_move_bps BETWEEN 100 AND 10000),
    deliverable_spot_verified BOOLEAN NOT NULL CHECK (deliverable_spot_verified),
    dividend_events_verified BOOLEAN NOT NULL CHECK (dividend_events_verified),
    corporate_actions_verified BOOLEAN NOT NULL CHECK (corporate_actions_verified),
    physical_delivery_reviewed BOOLEAN NOT NULL CHECK (physical_delivery_reviewed),
    costs_reviewed BOOLEAN NOT NULL CHECK (costs_reviewed),
    spot_source_digest CHAR(64) NOT NULL CHECK (spot_source_digest ~ '^[a-f0-9]{64}$'),
    futures_source_digest CHAR(64) NOT NULL CHECK (futures_source_digest ~ '^[a-f0-9]{64}$'),
    contract_source_digest CHAR(64) NOT NULL CHECK (contract_source_digest ~ '^[a-f0-9]{64}$'),
    carry_cost_source_digest CHAR(64) NOT NULL CHECK (
        carry_cost_source_digest ~ '^[a-f0-9]{64}$'),
    spot_calendar_evidence_id UUID NOT NULL
        REFERENCES golden_historical_exchange_session_evidence(evidence_id) ON DELETE RESTRICT,
    futures_calendar_evidence_id UUID NOT NULL
        REFERENCES golden_historical_exchange_session_evidence(evidence_id) ON DELETE RESTRICT,
    spot_rights_receipt_id UUID NOT NULL
        REFERENCES golden_source_rights_receipt(receipt_id) ON DELETE RESTRICT,
    futures_rights_receipt_id UUID NOT NULL
        REFERENCES golden_source_rights_receipt(receipt_id) ON DELETE RESTRICT,
    carry_cost_rights_receipt_id UUID NOT NULL
        REFERENCES golden_source_rights_receipt(receipt_id) ON DELETE RESTRICT,
    retain_until DATE NOT NULL,
    captured_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_fno_expiry_basis_provider_pair_session UNIQUE (
        provider_code, spot_key, futures_key, session_date),
    CONSTRAINT ck_fno_expiry_basis_prices CHECK (
        spot_ask >= spot_bid AND futures_ask >= futures_bid),
    CONSTRAINT ck_fno_expiry_basis_contract_date CHECK (
        listed_from <= session_date AND session_date < expiry),
    CONSTRAINT ck_fno_expiry_basis_origin_times CHECK (
        spot_exchange_at <= spot_quote_first_observed_at
        AND futures_exchange_at <= futures_quote_first_observed_at
        AND spot_quote_first_observed_at <= spot_final_first_available_at
        AND futures_quote_first_observed_at <= futures_final_first_available_at
        AND spot_final_first_available_at <= captured_at
        AND futures_final_first_available_at <= captured_at),
    CONSTRAINT ck_fno_expiry_basis_retention CHECK (
        retain_until >= session_date AND retain_until <= session_date + 90)
);
CREATE INDEX ix_fno_expiry_basis_pair
    ON fno_verified_expiry_basis_pair_evidence
    (spot_key, futures_key, session_date DESC);
CREATE INDEX ix_fno_expiry_basis_retention
    ON fno_verified_expiry_basis_pair_evidence (retain_until);

CREATE FUNCTION fno_reject_expiry_basis_evidence_mutation() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' AND OLD.retain_until < (now() AT TIME ZONE 'Asia/Kolkata')::DATE THEN
        RETURN OLD;
    END IF;
    RAISE EXCEPTION 'Expiry basis closing quote evidence is immutable before retention'
        USING ERRCODE = '23514';
END
$$;
CREATE TRIGGER tr_fno_expiry_basis_evidence_immutable
BEFORE UPDATE OR DELETE ON fno_verified_expiry_basis_pair_evidence
FOR EACH ROW EXECUTE FUNCTION fno_reject_expiry_basis_evidence_mutation();

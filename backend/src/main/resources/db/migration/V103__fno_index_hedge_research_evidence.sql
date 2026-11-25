-- STR-178: EMPTY, account-scoped, verified futures hedge research receipts.
-- Neither a market WebSocket quote nor broker login proves portfolio beta,
-- existing hedge reconciliation, displayed depth, margin, stress, or permission.
-- No INSERTs/default funds/assumed hedge orders. Data retained <= one day.
CREATE TABLE fno_index_hedge_research_evidence (
    evidence_id BIGSERIAL PRIMARY KEY,
    provider_code VARCHAR(32) NOT NULL CHECK (provider_code = 'UPSTOX_V3'),
    account_scope VARCHAR(80) NOT NULL CHECK (
        account_scope ~ '^[A-Za-z0-9_-]{8,80}$'),
    index_key VARCHAR(96) NOT NULL CHECK (
        index_key ~ '^NSE_INDEX\|[A-Za-z0-9 ._-]{1,64}$'),
    future_key VARCHAR(80) NOT NULL CHECK (
        future_key ~ '^NSE_FO\|[A-Za-z0-9._-]{1,64}$'),
    futures_expiry DATE NOT NULL,
    lot_size INTEGER NOT NULL CHECK (lot_size BETWEEN 1 AND 100000),
    signed_beta_exposure_inr NUMERIC(24,4) NOT NULL,
    exposure_calculated_at TIMESTAMPTZ NOT NULL,
    exposure_first_observed_at TIMESTAMPTZ NOT NULL,
    positions_reconciled BOOLEAN NOT NULL CHECK (positions_reconciled),
    existing_hedges_included BOOLEAN NOT NULL CHECK (existing_hedges_included),
    beta_model_verified BOOLEAN NOT NULL CHECK (beta_model_verified),
    future_bid NUMERIC(20,6) NOT NULL CHECK (future_bid > 0),
    future_ask NUMERIC(20,6) NOT NULL CHECK (future_ask > 0),
    future_bid_depth_units BIGINT NOT NULL CHECK (future_bid_depth_units >= 0),
    future_ask_depth_units BIGINT NOT NULL CHECK (future_ask_depth_units >= 0),
    future_open_interest_units BIGINT NOT NULL CHECK (future_open_interest_units > 0),
    exchange_at TIMESTAMPTZ NOT NULL,
    market_first_observed_at TIMESTAMPTZ NOT NULL,
    available_cash_inr NUMERIC(24,4) NOT NULL CHECK (available_cash_inr >= 0),
    initial_margin_per_lot_inr NUMERIC(24,4) NOT NULL CHECK (initial_margin_per_lot_inr > 0),
    variation_reserve_per_lot_inr NUMERIC(24,4) NOT NULL CHECK (variation_reserve_per_lot_inr > 0),
    index_shock_bps INTEGER NOT NULL CHECK (index_shock_bps BETWEEN 100 AND 5000),
    basis_stress_per_lot_inr NUMERIC(24,4) NOT NULL CHECK (basis_stress_per_lot_inr > 0),
    all_in_round_trip_cost_per_lot_inr NUMERIC(24,4) NOT NULL CHECK (all_in_round_trip_cost_per_lot_inr > 0),
    max_hedge_lots INTEGER NOT NULL CHECK (max_hedge_lots BETWEEN 1 AND 100),
    max_bid_ask_spread_bps INTEGER NOT NULL CHECK (max_bid_ask_spread_bps BETWEEN 1 AND 1000),
    minimum_risk_reduction_bps INTEGER NOT NULL CHECK (minimum_risk_reduction_bps BETWEEN 1 AND 9900),
    scenario_reviewed BOOLEAN NOT NULL CHECK (scenario_reviewed),
    costs_reviewed BOOLEAN NOT NULL CHECK (costs_reviewed),
    portfolio_evidence_sha256 CHAR(64) NOT NULL CHECK (
        portfolio_evidence_sha256 ~ '^[a-f0-9]{64}$'),
    beta_model_evidence_sha256 CHAR(64) NOT NULL CHECK (
        beta_model_evidence_sha256 ~ '^[a-f0-9]{64}$'),
    market_depth_evidence_sha256 CHAR(64) NOT NULL CHECK (
        market_depth_evidence_sha256 ~ '^[a-f0-9]{64}$'),
    broker_margin_evidence_sha256 CHAR(64) NOT NULL CHECK (
        broker_margin_evidence_sha256 ~ '^[a-f0-9]{64}$'),
    room_risk_evidence_sha256 CHAR(64) NOT NULL CHECK (
        room_risk_evidence_sha256 ~ '^[a-f0-9]{64}$'),
    market_rights_receipt_id UUID NOT NULL
        REFERENCES golden_source_rights_receipt(receipt_id) ON DELETE RESTRICT,
    portfolio_rights_receipt_id UUID NOT NULL
        REFERENCES golden_source_rights_receipt(receipt_id) ON DELETE RESTRICT,
    valid_until TIMESTAMPTZ NOT NULL,
    retain_until DATE NOT NULL,
    captured_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_fno_index_hedge_proof UNIQUE (
        provider_code, account_scope, future_key, market_first_observed_at),
    CONSTRAINT ck_fno_index_hedge_price CHECK (future_ask >= future_bid),
    CONSTRAINT ck_fno_index_hedge_temporal CHECK (
        exposure_calculated_at <= exposure_first_observed_at
        AND exchange_at <= market_first_observed_at
        AND exposure_first_observed_at <= captured_at
        AND market_first_observed_at <= captured_at
        AND valid_until > market_first_observed_at
        AND valid_until <= exchange_at + INTERVAL '15 seconds'),
    CONSTRAINT ck_fno_index_hedge_retention CHECK (
        retain_until >= (market_first_observed_at AT TIME ZONE 'Asia/Kolkata')::DATE
        AND retain_until <= (market_first_observed_at AT TIME ZONE 'Asia/Kolkata')::DATE + 1)
);
CREATE INDEX ix_fno_index_hedge_scope ON fno_index_hedge_research_evidence
    (account_scope, future_key, market_first_observed_at DESC);
CREATE INDEX ix_fno_index_hedge_retention ON fno_index_hedge_research_evidence
    (retain_until);

CREATE FUNCTION fno_reject_index_hedge_evidence_mutation() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' AND OLD.retain_until < (now() AT TIME ZONE 'Asia/Kolkata')::DATE THEN
        RETURN OLD;
    END IF;
    RAISE EXCEPTION 'Index-futures hedge receipts immutable before retention expiry'
        USING ERRCODE = '23514';
END
$$;
CREATE TRIGGER tr_fno_index_hedge_research_immutable
BEFORE UPDATE OR DELETE ON fno_index_hedge_research_evidence
FOR EACH ROW EXECUTE FUNCTION fno_reject_index_hedge_evidence_mutation();

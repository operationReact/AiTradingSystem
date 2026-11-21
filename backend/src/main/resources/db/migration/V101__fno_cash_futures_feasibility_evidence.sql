-- STR-175: EMPTY broker/account-scoped research evidence for one cash-stock BUY +
-- same-stock futures SELL. No prices, funding, fees or risk reserves are defaulted.
-- Rights receipts and proof hashes are REFERENCES, never inferred from logins.
-- Quotes are read separately from the existing shared feed. This table attests
-- contemporaneous L1 depth, financial assumptions, margin and settlement facts.

CREATE TABLE fno_cash_futures_feasibility_evidence (
    evidence_id BIGSERIAL PRIMARY KEY,
    provider_code VARCHAR(32) NOT NULL CHECK (
        provider_code IN ('UPSTOX_V3', 'ANGEL_ONE_SMARTAPI')),
    account_scope VARCHAR(80) NOT NULL
        CHECK (account_scope ~ '^[A-Za-z0-9_-]{8,80}$'),
    spot_key VARCHAR(80) NOT NULL
        CHECK (spot_key ~ '^NSE_EQ\|[A-Za-z0-9._-]{1,64}$'),
    future_key VARCHAR(80) NOT NULL
        CHECK (future_key ~ '^NSE_FO\|[A-Za-z0-9._-]{1,64}$'),
    futures_expiry DATE NOT NULL,
    lot_size INTEGER NOT NULL CHECK (lot_size BETWEEN 1 AND 100000),
    spot_ask_depth_units BIGINT NOT NULL CHECK (spot_ask_depth_units >= 0),
    futures_bid_depth_units BIGINT NOT NULL CHECK (futures_bid_depth_units >= 0),
    spot_ask_at_depth NUMERIC(20,6) NOT NULL CHECK (spot_ask_at_depth > 0),
    futures_bid_at_depth NUMERIC(20,6) NOT NULL CHECK (futures_bid_at_depth > 0),
    depth_exchange_at TIMESTAMPTZ NOT NULL,
    depth_first_available_at TIMESTAMPTZ NOT NULL,
    valid_until TIMESTAMPTZ NOT NULL,
    retain_until DATE NOT NULL,
    funding_annual_bps NUMERIC(18,6) NOT NULL CHECK (funding_annual_bps BETWEEN 0 AND 5000),
    dividend_annual_bps NUMERIC(18,6) NOT NULL CHECK (dividend_annual_bps BETWEEN 0 AND 10000),
    stock_borrow_annual_bps NUMERIC(18,6) NOT NULL CHECK (stock_borrow_annual_bps BETWEEN 0 AND 10000),
    basis_round_trip_cost_bps NUMERIC(18,6) NOT NULL CHECK (basis_round_trip_cost_bps BETWEEN 1 AND 2000),
    basis_min_net_edge_bps NUMERIC(18,6) NOT NULL CHECK (basis_min_net_edge_bps BETWEEN 1 AND 2000),
    available_cash_inr NUMERIC(20,4) NOT NULL CHECK (available_cash_inr >= 0),
    futures_initial_margin_inr NUMERIC(20,4) NOT NULL CHECK (futures_initial_margin_inr > 0),
    stressed_variation_margin_inr NUMERIC(20,4) NOT NULL CHECK (stressed_variation_margin_inr > 0),
    delivery_buffer_inr NUMERIC(20,4) NOT NULL CHECK (delivery_buffer_inr > 0),
    extra_slippage_per_share_inr NUMERIC(20,4) NOT NULL CHECK (extra_slippage_per_share_inr >= 0),
    incremental_tax_fees_per_lot_inr NUMERIC(20,4) NOT NULL CHECK (incremental_tax_fees_per_lot_inr >= 0),
    dividend_uncertainty_per_lot_inr NUMERIC(20,4) NOT NULL CHECK (dividend_uncertainty_per_lot_inr >= 0),
    corporate_action_buffer_per_lot_inr NUMERIC(20,4) NOT NULL CHECK (corporate_action_buffer_per_lot_inr >= 0),
    min_stressed_net_per_lot_inr NUMERIC(20,4) NOT NULL CHECK (min_stressed_net_per_lot_inr > 0),
    spot_rights_receipt_id UUID NOT NULL
        REFERENCES golden_source_rights_receipt(receipt_id) ON DELETE RESTRICT,
    futures_rights_receipt_id UUID NOT NULL
        REFERENCES golden_source_rights_receipt(receipt_id) ON DELETE RESTRICT,
    depth_evidence_sha256 CHAR(64) NOT NULL
        CHECK (depth_evidence_sha256 ~ '^[a-f0-9]{64}$'),
    margin_evidence_sha256 CHAR(64) NOT NULL
        CHECK (margin_evidence_sha256 ~ '^[a-f0-9]{64}$'),
    funding_evidence_sha256 CHAR(64) NOT NULL
        CHECK (funding_evidence_sha256 ~ '^[a-f0-9]{64}$'),
    settlement_evidence_sha256 CHAR(64) NOT NULL
        CHECK (settlement_evidence_sha256 ~ '^[a-f0-9]{64}$'),
    fees_evidence_sha256 CHAR(64) NOT NULL
        CHECK (fees_evidence_sha256 ~ '^[a-f0-9]{64}$'),
    corporate_events_evidence_sha256 CHAR(64) NOT NULL
        CHECK (corporate_events_evidence_sha256 ~ '^[a-f0-9]{64}$'),
    delivery_identity_verified BOOLEAN NOT NULL CHECK (delivery_identity_verified),
    independent_fees_verified BOOLEAN NOT NULL CHECK (independent_fees_verified),
    stress_margin_verified BOOLEAN NOT NULL CHECK (stress_margin_verified),
    settlement_funding_verified BOOLEAN NOT NULL CHECK (settlement_funding_verified),
    statutory_fees_verified BOOLEAN NOT NULL CHECK (statutory_fees_verified),
    dividend_events_verified BOOLEAN NOT NULL CHECK (dividend_events_verified),
    corporate_actions_verified BOOLEAN NOT NULL CHECK (corporate_actions_verified),
    captured_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_fno_cash_futures_depth_proof UNIQUE (
        provider_code, account_scope, spot_key, future_key, depth_first_available_at),
    CONSTRAINT ck_fno_cash_futures_retention CHECK (
        retain_until >= (depth_first_available_at AT TIME ZONE 'Asia/Kolkata')::DATE
        AND retain_until <= (depth_first_available_at AT TIME ZONE 'Asia/Kolkata')::DATE + 7),
    CONSTRAINT ck_fno_cash_futures_timing CHECK (
        depth_exchange_at <= depth_first_available_at
        AND depth_first_available_at <= captured_at
        AND captured_at <= depth_first_available_at + INTERVAL '15 seconds'
        AND valid_until > depth_first_available_at
        AND valid_until <= depth_exchange_at + INTERVAL '15 seconds')
);

CREATE INDEX ix_fno_cash_futures_scope
    ON fno_cash_futures_feasibility_evidence (
        account_scope, spot_key, future_key, depth_first_available_at DESC);

-- Prevent editing or silently replacing evidence; only retention-expired erasure is allowed.
CREATE FUNCTION fno_reject_cash_futures_evidence_mutation() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' AND OLD.retain_until < (now() AT TIME ZONE 'Asia/Kolkata')::DATE THEN
        RETURN OLD;
    END IF;
    RAISE EXCEPTION 'Cash-futures feasibility evidence is append-only'
        USING ERRCODE = '23514';
END
$$;
CREATE TRIGGER tr_fno_cash_futures_evidence_immutable
BEFORE UPDATE OR DELETE ON fno_cash_futures_feasibility_evidence
FOR EACH ROW EXECUTE FUNCTION fno_reject_cash_futures_evidence_mutation();

-- No INSERT, broker token, account data seed, scheduled market download, or order.

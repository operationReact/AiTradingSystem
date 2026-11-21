-- STR-183 & STR-184: EMPTY provider-scoped source and risk proof for
-- two contemporaneous NSE NIFTY options of the SAME expiry and lot.
-- Premium quotes and signed risk/costs are research-only, never real fills.
CREATE TABLE fno_verified_nifty_debit_spread_source (
    evidence_id BIGSERIAL PRIMARY KEY,
    provider_code VARCHAR(32) NOT NULL CHECK (
        provider_code IN ('UPSTOX_ANALYTICS', 'ANGEL_ONE_SMARTAPI')),
    spread_type VARCHAR(12) NOT NULL CHECK (
        spread_type IN ('BULL_CALL', 'BEAR_PUT')),
    index_key VARCHAR(80) NOT NULL CHECK (index_key = 'NSE_INDEX|Nifty 50'),
    lower_option_key VARCHAR(96) NOT NULL CHECK (
        lower_option_key ~ '^NSE_FO\|[A-Za-z0-9._-]{1,80}$'),
    higher_option_key VARCHAR(96) NOT NULL CHECK (
        higher_option_key ~ '^NSE_FO\|[A-Za-z0-9._-]{1,80}$'),
    option_type CHAR(2) NOT NULL CHECK (option_type IN ('CE', 'PE')),
    lower_strike NUMERIC(20,6) NOT NULL CHECK (lower_strike > 0),
    higher_strike NUMERIC(20,6) NOT NULL CHECK (higher_strike > 0),
    lower_tick_size NUMERIC(20,6) NOT NULL CHECK (lower_tick_size > 0),
    higher_tick_size NUMERIC(20,6) NOT NULL CHECK (higher_tick_size > 0),
    lower_listed_from DATE NOT NULL,
    higher_listed_from DATE NOT NULL,
    expiry DATE NOT NULL,
    lot_size INTEGER NOT NULL CHECK (lot_size BETWEEN 1 AND 100000),
    index_price NUMERIC(20,6) NOT NULL CHECK (index_price > 0),
    index_exchange_at TIMESTAMPTZ NOT NULL,
    index_first_observed_at TIMESTAMPTZ NOT NULL,
    lower_bid NUMERIC(20,6) NOT NULL CHECK (lower_bid > 0),
    lower_ask NUMERIC(20,6) NOT NULL CHECK (lower_ask > 0),
    lower_bid_depth_lots BIGINT NOT NULL CHECK (lower_bid_depth_lots > 0),
    lower_ask_depth_lots BIGINT NOT NULL CHECK (lower_ask_depth_lots > 0),
    lower_oi_lots BIGINT NOT NULL CHECK (lower_oi_lots > 0),
    lower_exchange_at TIMESTAMPTZ NOT NULL,
    lower_first_observed_at TIMESTAMPTZ NOT NULL,
    higher_bid NUMERIC(20,6) NOT NULL CHECK (higher_bid > 0),
    higher_ask NUMERIC(20,6) NOT NULL CHECK (higher_ask > 0),
    higher_bid_depth_lots BIGINT NOT NULL CHECK (higher_bid_depth_lots > 0),
    higher_ask_depth_lots BIGINT NOT NULL CHECK (higher_ask_depth_lots > 0),
    higher_oi_lots BIGINT NOT NULL CHECK (higher_oi_lots > 0),
    higher_exchange_at TIMESTAMPTZ NOT NULL,
    higher_first_observed_at TIMESTAMPTZ NOT NULL,
    buy_slippage_bps INTEGER NOT NULL CHECK (buy_slippage_bps BETWEEN 0 AND 1000),
    sell_slippage_bps INTEGER NOT NULL CHECK (sell_slippage_bps BETWEEN 0 AND 1000),
    all_in_spread_fees_inr NUMERIC(20,4) NOT NULL CHECK (all_in_spread_fees_inr > 0),
    max_each_leg_spread_bps INTEGER NOT NULL CHECK (
        max_each_leg_spread_bps BETWEEN 1 AND 3000),
    minimum_bid_ask_depth_lots BIGINT NOT NULL CHECK (minimum_bid_ask_depth_lots > 0),
    minimum_open_interest_lots BIGINT NOT NULL CHECK (minimum_open_interest_lots > 0),
    minimum_max_reward_risk_bps INTEGER NOT NULL CHECK (
        minimum_max_reward_risk_bps BETWEEN 1 AND 50000),
    research_capital_inr NUMERIC(20,4) NOT NULL CHECK (research_capital_inr > 0),
    max_capital_allocation_bps INTEGER NOT NULL CHECK (
        max_capital_allocation_bps BETWEEN 100 AND 10000),
    max_strike_distance_bps INTEGER NOT NULL CHECK (
        max_strike_distance_bps BETWEEN 1 AND 3000),
    contract_master_verified BOOLEAN NOT NULL CHECK (contract_master_verified),
    both_depths_verified BOOLEAN NOT NULL CHECK (both_depths_verified),
    oi_lot_normalization_verified BOOLEAN NOT NULL CHECK (oi_lot_normalization_verified),
    costs_taxes_verified BOOLEAN NOT NULL CHECK (costs_taxes_verified),
    capital_budget_verified BOOLEAN NOT NULL CHECK (capital_budget_verified),
    lower_quote_source_sha256 CHAR(64) NOT NULL CHECK (
        lower_quote_source_sha256 ~ '^[a-f0-9]{64}$'),
    higher_quote_source_sha256 CHAR(64) NOT NULL CHECK (
        higher_quote_source_sha256 ~ '^[a-f0-9]{64}$'),
    index_quote_source_sha256 CHAR(64) NOT NULL CHECK (
        index_quote_source_sha256 ~ '^[a-f0-9]{64}$'),
    contract_master_sha256 CHAR(64) NOT NULL CHECK (
        contract_master_sha256 ~ '^[a-f0-9]{64}$'),
    cost_budget_source_sha256 CHAR(64) NOT NULL CHECK (
        cost_budget_source_sha256 ~ '^[a-f0-9]{64}$'),
    index_rights_receipt_id UUID NOT NULL
        REFERENCES golden_source_rights_receipt(receipt_id) ON DELETE RESTRICT,
    lower_quote_rights_receipt_id UUID NOT NULL
        REFERENCES golden_source_rights_receipt(receipt_id) ON DELETE RESTRICT,
    higher_quote_rights_receipt_id UUID NOT NULL
        REFERENCES golden_source_rights_receipt(receipt_id) ON DELETE RESTRICT,
    master_rights_receipt_id UUID NOT NULL
        REFERENCES golden_source_rights_receipt(receipt_id) ON DELETE RESTRICT,
    cost_budget_rights_receipt_id UUID NOT NULL
        REFERENCES golden_source_rights_receipt(receipt_id) ON DELETE RESTRICT,
    valid_until TIMESTAMPTZ NOT NULL,
    retain_until DATE NOT NULL,
    captured_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_fno_debit_spread_proof UNIQUE (
        provider_code, spread_type, lower_option_key, higher_option_key,
        lower_exchange_at, higher_exchange_at),
    CONSTRAINT ck_fno_debit_spread_structure CHECK (
        lower_option_key <> higher_option_key
        AND higher_strike > lower_strike
        AND (spread_type = 'BULL_CALL' AND option_type = 'CE'
            OR spread_type = 'BEAR_PUT' AND option_type = 'PE')
        AND lower_ask >= lower_bid AND higher_ask >= higher_bid
        AND lower_quote_source_sha256 <> higher_quote_source_sha256),
    CONSTRAINT ck_fno_debit_spread_origins CHECK (
        index_exchange_at <= index_first_observed_at
        AND lower_exchange_at <= lower_first_observed_at
        AND higher_exchange_at <= higher_first_observed_at
        AND index_first_observed_at <= captured_at
        AND lower_first_observed_at <= captured_at
        AND higher_first_observed_at <= captured_at
        AND valid_until > higher_first_observed_at
        AND valid_until > lower_first_observed_at
        AND valid_until <= lower_exchange_at + INTERVAL '180 seconds'
        AND valid_until <= higher_exchange_at + INTERVAL '180 seconds'),
    CONSTRAINT ck_fno_debit_spread_expiry CHECK (
        expiry >= (lower_exchange_at AT TIME ZONE 'Asia/Kolkata')::DATE
        AND lower_listed_from <= (lower_exchange_at AT TIME ZONE 'Asia/Kolkata')::DATE
        AND higher_listed_from <= (higher_exchange_at AT TIME ZONE 'Asia/Kolkata')::DATE),
    CONSTRAINT ck_fno_debit_spread_retention CHECK (
        retain_until >= (lower_exchange_at AT TIME ZONE 'Asia/Kolkata')::DATE
        AND retain_until <= (lower_exchange_at AT TIME ZONE 'Asia/Kolkata')::DATE + 1)
);
CREATE INDEX ix_fno_debit_spread_keys
    ON fno_verified_nifty_debit_spread_source
    (spread_type, lower_option_key, higher_option_key, lower_exchange_at DESC);
CREATE INDEX ix_fno_debit_spread_retention
    ON fno_verified_nifty_debit_spread_source (retain_until);

CREATE FUNCTION fno_reject_debit_spread_mutation() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE'
        AND OLD.retain_until < (now() AT TIME ZONE 'Asia/Kolkata')::DATE THEN
        RETURN OLD;
    END IF;
    RAISE EXCEPTION 'Debit-spread research source evidence is immutable'
        USING ERRCODE = '23514';
END
$$;
CREATE TRIGGER tr_fno_debit_spread_immutable
BEFORE UPDATE OR DELETE ON fno_verified_nifty_debit_spread_source
FOR EACH ROW EXECUTE FUNCTION fno_reject_debit_spread_mutation();
-- No source seeding, no broker order or margin side effects.

-- STR-181/182: EMPTY NIFTY source custody. No quote or capital fields
-- are inferred from a downloaded candle, trading login, or ticker name.
-- Expiry options, original availability, full lot depth/OI and costs require
-- provider-specific currently verified research-use receipts.
CREATE TABLE fno_verified_nifty_daily_close (
    evidence_id BIGSERIAL PRIMARY KEY,
    provider_code VARCHAR(32) NOT NULL CHECK (
        provider_code IN ('UPSTOX_ANALYTICS', 'ANGEL_ONE_SMARTAPI')),
    index_key VARCHAR(80) NOT NULL CHECK (index_key = 'NSE_INDEX|Nifty 50'),
    session_date DATE NOT NULL,
    close_price NUMERIC(20,6) NOT NULL CHECK (close_price > 0),
    first_available_at TIMESTAMPTZ NOT NULL,
    calendar_evidence_id UUID NOT NULL
        REFERENCES golden_historical_exchange_session_evidence(evidence_id)
        ON DELETE RESTRICT,
    index_close_rights_receipt_id UUID NOT NULL
        REFERENCES golden_source_rights_receipt(receipt_id) ON DELETE RESTRICT,
    index_close_digest CHAR(64) NOT NULL CHECK (
        index_close_digest ~ '^[a-f0-9]{64}$'),
    retain_until DATE NOT NULL,
    captured_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_fno_nifty_close_session UNIQUE (
        provider_code, index_key, session_date),
    CONSTRAINT ck_fno_nifty_close_retention CHECK (
        retain_until >= session_date AND retain_until <= session_date + 365),
    CONSTRAINT ck_fno_nifty_close_first_known CHECK (
        first_available_at <= captured_at)
);
CREATE INDEX ix_fno_nifty_close_window ON fno_verified_nifty_daily_close
    (index_key, session_date DESC);

CREATE TABLE fno_verified_nifty_long_option_quote (
    evidence_id BIGSERIAL PRIMARY KEY,
    provider_code VARCHAR(32) NOT NULL CHECK (
        provider_code IN ('UPSTOX_ANALYTICS', 'ANGEL_ONE_SMARTAPI')),
    index_key VARCHAR(80) NOT NULL CHECK (index_key = 'NSE_INDEX|Nifty 50'),
    instrument_key VARCHAR(96) NOT NULL CHECK (
        instrument_key ~ '^NSE_FO\|[A-Za-z0-9._-]{1,80}$'),
    option_type CHAR(2) NOT NULL CHECK (option_type IN ('CE', 'PE')),
    listed_from DATE NOT NULL,
    expiry DATE NOT NULL,
    lot_size INTEGER NOT NULL CHECK (lot_size BETWEEN 1 AND 100000),
    strike NUMERIC(20,6) NOT NULL CHECK (strike > 0),
    tick_size NUMERIC(20,6) NOT NULL CHECK (tick_size > 0),
    index_price NUMERIC(20,6) NOT NULL CHECK (index_price > 0),
    index_exchange_at TIMESTAMPTZ NOT NULL,
    index_first_observed_at TIMESTAMPTZ NOT NULL,
    option_bid NUMERIC(20,6) NOT NULL CHECK (option_bid > 0),
    option_ask NUMERIC(20,6) NOT NULL CHECK (option_ask > 0),
    ask_depth_lots BIGINT NOT NULL CHECK (ask_depth_lots > 0),
    open_interest_lots BIGINT NOT NULL CHECK (open_interest_lots > 0),
    option_exchange_at TIMESTAMPTZ NOT NULL,
    option_first_observed_at TIMESTAMPTZ NOT NULL,
    hypothetical_move_at_expiry_bps INTEGER NOT NULL CHECK (
        hypothetical_move_at_expiry_bps BETWEEN 100 AND 3000),
    buy_slippage_bps INTEGER NOT NULL CHECK (buy_slippage_bps BETWEEN 0 AND 1000),
    all_in_fees_per_lot_inr NUMERIC(20,4) NOT NULL CHECK (
        all_in_fees_per_lot_inr > 0),
    minimum_scenario_reward_risk_bps INTEGER NOT NULL CHECK (
        minimum_scenario_reward_risk_bps BETWEEN 1000 AND 50000),
    maximum_option_spread_bps INTEGER NOT NULL CHECK (
        maximum_option_spread_bps BETWEEN 1 AND 3000),
    maximum_strike_distance_bps INTEGER NOT NULL CHECK (
        maximum_strike_distance_bps BETWEEN 1 AND 3000),
    minimum_ask_depth_lots BIGINT NOT NULL CHECK (minimum_ask_depth_lots > 0),
    minimum_open_interest_lots BIGINT NOT NULL CHECK (minimum_open_interest_lots > 0),
    research_capital_inr NUMERIC(20,4) NOT NULL CHECK (research_capital_inr > 0),
    maximum_capital_allocation_bps INTEGER NOT NULL CHECK (
        maximum_capital_allocation_bps BETWEEN 100 AND 10000),
    contract_master_verified BOOLEAN NOT NULL CHECK (contract_master_verified),
    lot_units_reconciled BOOLEAN NOT NULL CHECK (lot_units_reconciled),
    fee_tax_schedule_verified BOOLEAN NOT NULL CHECK (fee_tax_schedule_verified),
    research_budget_verified BOOLEAN NOT NULL CHECK (research_budget_verified),
    contract_master_sha256 CHAR(64) NOT NULL CHECK (
        contract_master_sha256 ~ '^[a-f0-9]{64}$'),
    option_chain_source_sha256 CHAR(64) NOT NULL CHECK (
        option_chain_source_sha256 ~ '^[a-f0-9]{64}$'),
    underlying_tick_source_sha256 CHAR(64) NOT NULL CHECK (
        underlying_tick_source_sha256 ~ '^[a-f0-9]{64}$'),
    fee_tax_source_sha256 CHAR(64) NOT NULL CHECK (
        fee_tax_source_sha256 ~ '^[a-f0-9]{64}$'),
    risk_budget_source_sha256 CHAR(64) NOT NULL CHECK (
        risk_budget_source_sha256 ~ '^[a-f0-9]{64}$'),
    underlying_tick_rights_receipt_id UUID NOT NULL
        REFERENCES golden_source_rights_receipt(receipt_id) ON DELETE RESTRICT,
    option_quote_rights_receipt_id UUID NOT NULL
        REFERENCES golden_source_rights_receipt(receipt_id) ON DELETE RESTRICT,
    option_master_rights_receipt_id UUID NOT NULL
        REFERENCES golden_source_rights_receipt(receipt_id) ON DELETE RESTRICT,
    fee_risk_rights_receipt_id UUID NOT NULL
        REFERENCES golden_source_rights_receipt(receipt_id) ON DELETE RESTRICT,
    valid_until TIMESTAMPTZ NOT NULL,
    retain_until DATE NOT NULL,
    captured_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_fno_long_option_proof UNIQUE (
        provider_code, instrument_key, option_exchange_at),
    CONSTRAINT ck_fno_long_option_quote_prices CHECK (option_ask >= option_bid),
    CONSTRAINT ck_fno_long_option_expiry CHECK (
        expiry >= (option_exchange_at AT TIME ZONE 'Asia/Kolkata')::DATE
        AND listed_from <= (option_exchange_at AT TIME ZONE 'Asia/Kolkata')::DATE),
    CONSTRAINT ck_fno_long_option_original_time CHECK (
        index_exchange_at <= index_first_observed_at
        AND option_exchange_at <= option_first_observed_at
        AND index_first_observed_at <= captured_at
        AND option_first_observed_at <= captured_at
        AND valid_until > option_first_observed_at
        AND valid_until <= option_exchange_at + INTERVAL '180 seconds'),
    CONSTRAINT ck_fno_long_option_retention CHECK (
        retain_until >= (option_exchange_at AT TIME ZONE 'Asia/Kolkata')::DATE
        AND retain_until <= (option_exchange_at AT TIME ZONE 'Asia/Kolkata')::DATE + 1)
);
CREATE INDEX ix_fno_nifty_option_contract_event ON fno_verified_nifty_long_option_quote
    (instrument_key, option_exchange_at DESC);
CREATE INDEX ix_fno_nifty_option_retention ON fno_verified_nifty_long_option_quote
    (retain_until);

-- Every evidence row is append-only until explicit retention expiry.
CREATE FUNCTION fno_reject_nifty_source_mutation() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE'
        AND OLD.retain_until < (now() AT TIME ZONE 'Asia/Kolkata')::DATE THEN
        RETURN OLD;
    END IF;
    RAISE EXCEPTION 'Verified NIFTY option source evidence is append-only'
        USING ERRCODE = '23514';
END
$$;
CREATE TRIGGER tr_fno_nifty_close_immutable
BEFORE UPDATE OR DELETE ON fno_verified_nifty_daily_close
FOR EACH ROW EXECUTE FUNCTION fno_reject_nifty_source_mutation();
CREATE TRIGGER tr_fno_nifty_option_immutable
BEFORE UPDATE OR DELETE ON fno_verified_nifty_long_option_quote
FOR EACH ROW EXECUTE FUNCTION fno_reject_nifty_source_mutation();
-- No inserts, tokens, synthetic candles, guessed strikes or order privileges.

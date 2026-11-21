-- STR-189: EMPTY independently attested combined fees and room risk for
-- ONE already-settled full stock lot + long PE + short CE, same expiry/lot.
-- Never add two unrelated single-option fee estimates or assume broker fills.
CREATE TABLE fno_verified_owned_equity_collar_cost (
 evidence_id BIGSERIAL PRIMARY KEY,
 provider_code VARCHAR(32) NOT NULL CHECK (provider_code IN
    ('UPSTOX_ANALYTICS','ANGEL_ONE_SMARTAPI')),
 account_scope VARCHAR(80) NOT NULL CHECK (account_scope ~ '^[A-Za-z0-9_-]{8,80}$'),
 equity_key VARCHAR(80) NOT NULL CHECK (equity_key ~ '^NSE_EQ\|[A-Za-z0-9._-]{1,64}$'),
 pe_key VARCHAR(96) NOT NULL CHECK (pe_key ~ '^NSE_FO\|[A-Za-z0-9._-]{1,64}$'),
 ce_key VARCHAR(96) NOT NULL CHECK (ce_key ~ '^NSE_FO\|[A-Za-z0-9._-]{1,64}$'),
 expiry DATE NOT NULL,
 lot_size INTEGER NOT NULL CHECK (lot_size BETWEEN 1 AND 100000),
 equity_bid NUMERIC(20,6) NOT NULL CHECK (equity_bid > 0),
 equity_ask NUMERIC(20,6) NOT NULL CHECK (equity_ask > 0),
 equity_exchange_at TIMESTAMPTZ NOT NULL,
 equity_first_known_at TIMESTAMPTZ NOT NULL,
 equity_quote_source_sha256 CHAR(64) NOT NULL CHECK (equity_quote_source_sha256 ~ '^[a-f0-9]{64}$'),
 equity_quote_rights_receipt_id UUID NOT NULL REFERENCES
    golden_source_rights_receipt(receipt_id) ON DELETE RESTRICT,
 downside_scenario_bps INTEGER NOT NULL CHECK (downside_scenario_bps BETWEEN 0 AND 9500),
 drawdown_trigger_bps INTEGER NOT NULL CHECK (drawdown_trigger_bps BETWEEN 100 AND 5000),
 put_buy_slippage_bps INTEGER NOT NULL CHECK (put_buy_slippage_bps BETWEEN 0 AND 1000),
 call_sell_slippage_bps INTEGER NOT NULL CHECK (call_sell_slippage_bps BETWEEN 0 AND 1000),
 max_each_option_spread_bps INTEGER NOT NULL CHECK (max_each_option_spread_bps BETWEEN 1 AND 3000),
 minimum_bid_ask_depth_lots BIGINT NOT NULL CHECK (minimum_bid_ask_depth_lots > 0),
 minimum_open_interest_lots BIGINT NOT NULL CHECK (minimum_open_interest_lots > 0),
 max_strike_distance_bps INTEGER NOT NULL CHECK (max_strike_distance_bps BETWEEN 1 AND 3000),
 all_in_fees_per_collar_lot_inr NUMERIC(22,4) NOT NULL CHECK (all_in_fees_per_collar_lot_inr > 0),
 research_capital_inr NUMERIC(22,4) NOT NULL CHECK (research_capital_inr > 0),
 max_stock_notional_bps INTEGER NOT NULL CHECK (max_stock_notional_bps BETWEEN 100 AND 10000),
 max_net_debit_bps INTEGER NOT NULL CHECK (max_net_debit_bps BETWEEN 100 AND 10000),
 combined_fees_not_double_counted BOOLEAN NOT NULL CHECK (combined_fees_not_double_counted),
 physical_delivery_and_assignment_reviewed BOOLEAN NOT NULL CHECK
    (physical_delivery_and_assignment_reviewed),
 broker_settled_shares_reconciled BOOLEAN NOT NULL CHECK (broker_settled_shares_reconciled),
 source_model_sha256 CHAR(64) NOT NULL CHECK (source_model_sha256 ~ '^[a-f0-9]{64}$'),
 fee_cost_rights_receipt_id UUID NOT NULL REFERENCES
    golden_source_rights_receipt(receipt_id) ON DELETE RESTRICT,
 captured_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 first_known_at TIMESTAMPTZ NOT NULL,
 valid_until TIMESTAMPTZ NOT NULL,
 retain_until DATE NOT NULL,
 CONSTRAINT uq_fno_verified_equity_collar_cost UNIQUE (
    provider_code,account_scope,pe_key,ce_key,first_known_at),
 CONSTRAINT ck_fno_collar_quote_check CHECK (
    equity_ask >= equity_bid AND equity_exchange_at <= equity_first_known_at
    AND equity_first_known_at <= captured_at),
 CONSTRAINT ck_fno_collar_source_validity CHECK (
    pe_key <> ce_key AND first_known_at <= captured_at AND
    valid_until > first_known_at AND valid_until <= first_known_at + INTERVAL '300 seconds'),
 CONSTRAINT ck_fno_collar_cost_retention CHECK (
    retain_until >= (first_known_at AT TIME ZONE 'Asia/Kolkata')::DATE
    AND retain_until <= (first_known_at AT TIME ZONE 'Asia/Kolkata')::DATE + 1)
);
CREATE INDEX ix_fno_verified_collar_cost_scope
 ON fno_verified_owned_equity_collar_cost (account_scope,equity_key,pe_key,ce_key,first_known_at DESC);
CREATE FUNCTION fno_reject_collar_cost_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP='DELETE' AND OLD.retain_until < (now() AT TIME ZONE 'Asia/Kolkata')::DATE
 THEN RETURN OLD;
 END IF;
 RAISE EXCEPTION 'Verified collar cost evidence cannot be modified or prematurely removed'
 USING ERRCODE='23514';
END $$;
CREATE TRIGGER tr_fno_collar_cost_immutable
 BEFORE UPDATE OR DELETE ON fno_verified_owned_equity_collar_cost
 FOR EACH ROW EXECUTE FUNCTION fno_reject_collar_cost_mutation();

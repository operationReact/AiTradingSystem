-- STR-190/191: EMPTY, source-attested ONE long plus TWO short NIFTY options.
-- Calls have UNBOUNDED upside loss. Puts have SEVERE finite loss at S=0.
-- Stress scenarios are not risk caps, buying power or broker fills.
CREATE TABLE fno_verified_nifty_ratio_spread_source (
 evidence_id BIGSERIAL PRIMARY KEY,
 provider_code VARCHAR(32) NOT NULL CHECK (provider_code IN ('UPSTOX_ANALYTICS','ANGEL_ONE_SMARTAPI')),
 account_scope VARCHAR(80) NOT NULL CHECK (account_scope ~ '^[A-Za-z0-9_-]{8,80}$'),
 ratio_side VARCHAR(4) NOT NULL CHECK (ratio_side IN ('CALL','PUT')),
 index_key VARCHAR(80) NOT NULL CHECK (index_key='NSE_INDEX|Nifty 50'),
 long_key VARCHAR(96) NOT NULL CHECK (long_key ~ '^NSE_FO\|[A-Za-z0-9._-]{1,80}$'),
 short_key VARCHAR(96) NOT NULL CHECK (short_key ~ '^NSE_FO\|[A-Za-z0-9._-]{1,80}$'),
 option_kind CHAR(2) NOT NULL CHECK (option_kind IN ('CE','PE')),
 long_strike NUMERIC(20,6) NOT NULL CHECK (long_strike>0),
 short_strike NUMERIC(20,6) NOT NULL CHECK (short_strike>0),
 long_tick NUMERIC(20,6) NOT NULL CHECK (long_tick>0),
 short_tick NUMERIC(20,6) NOT NULL CHECK (short_tick>0),
 long_listed_from DATE NOT NULL,
 short_listed_from DATE NOT NULL,
 expiry DATE NOT NULL,
 lot_size INTEGER NOT NULL CHECK (lot_size BETWEEN 1 AND 100000),
 index_bid NUMERIC(20,6) NOT NULL CHECK (index_bid>0),
 index_ask NUMERIC(20,6) NOT NULL CHECK (index_ask>0),
 index_exchange_at TIMESTAMPTZ NOT NULL,
 index_first_known_at TIMESTAMPTZ NOT NULL,
 long_bid NUMERIC(20,6) NOT NULL CHECK (long_bid>0),
 long_ask NUMERIC(20,6) NOT NULL CHECK (long_ask>0),
 long_bid_depth_lots BIGINT NOT NULL CHECK (long_bid_depth_lots>0),
 long_ask_depth_lots BIGINT NOT NULL CHECK (long_ask_depth_lots>0),
 long_oi_lots BIGINT NOT NULL CHECK (long_oi_lots>0),
 long_exchange_at TIMESTAMPTZ NOT NULL,
 long_first_known_at TIMESTAMPTZ NOT NULL,
 short_bid NUMERIC(20,6) NOT NULL CHECK (short_bid>0),
 short_ask NUMERIC(20,6) NOT NULL CHECK (short_ask>0),
 short_bid_depth_lots BIGINT NOT NULL CHECK (short_bid_depth_lots>=2),
 short_ask_depth_lots BIGINT NOT NULL CHECK (short_ask_depth_lots>0),
 short_oi_lots BIGINT NOT NULL CHECK (short_oi_lots>0),
 short_exchange_at TIMESTAMPTZ NOT NULL,
 short_first_known_at TIMESTAMPTZ NOT NULL,
 buy_slippage_bps INTEGER NOT NULL CHECK (buy_slippage_bps BETWEEN 0 AND 1000),
 sell_slippage_bps INTEGER NOT NULL CHECK (sell_slippage_bps BETWEEN 0 AND 1000),
 max_option_spread_bps INTEGER NOT NULL CHECK (max_option_spread_bps BETWEEN 1 AND 3000),
 min_bid_ask_depth_lots BIGINT NOT NULL CHECK (min_bid_ask_depth_lots>0),
 min_oi_lots BIGINT NOT NULL CHECK (min_oi_lots>0),
 max_strike_distance_bps INTEGER NOT NULL CHECK (max_strike_distance_bps BETWEEN 1 AND 3000),
 total_three_leg_fees_inr NUMERIC(20,4) NOT NULL CHECK (total_three_leg_fees_inr>0),
 research_capital_inr NUMERIC(24,4) NOT NULL CHECK (research_capital_inr>0),
 max_loss_allocation_bps INTEGER NOT NULL CHECK (max_loss_allocation_bps BETWEEN 100 AND 10000),
 stress_underlying_price NUMERIC(20,6) NOT NULL CHECK (stress_underlying_price>0),
 min_peak_reward_bps INTEGER NOT NULL CHECK (min_peak_reward_bps BETWEEN 1 AND 50000),
 broker_margin_reserve_inr NUMERIC(24,4) NOT NULL CHECK (broker_margin_reserve_inr>0),
 independently_reconciled_margin BOOLEAN NOT NULL CHECK (independently_reconciled_margin),
 true_two_short_lots_depth BOOLEAN NOT NULL CHECK (true_two_short_lots_depth),
 three_leg_fees_not_double_counted BOOLEAN NOT NULL CHECK (three_leg_fees_not_double_counted),
 source_instrument_master_verified BOOLEAN NOT NULL CHECK (source_instrument_master_verified),
 stress_tail_risk_reviewed BOOLEAN NOT NULL CHECK (stress_tail_risk_reviewed),
 index_source_sha256 CHAR(64) NOT NULL CHECK (index_source_sha256 ~ '^[a-f0-9]{64}$'),
 long_source_sha256 CHAR(64) NOT NULL CHECK (long_source_sha256 ~ '^[a-f0-9]{64}$'),
 short_source_sha256 CHAR(64) NOT NULL CHECK (short_source_sha256 ~ '^[a-f0-9]{64}$'),
 contract_source_sha256 CHAR(64) NOT NULL CHECK (contract_source_sha256 ~ '^[a-f0-9]{64}$'),
 margin_cost_source_sha256 CHAR(64) NOT NULL CHECK (margin_cost_source_sha256 ~ '^[a-f0-9]{64}$'),
 index_rights_receipt_id UUID NOT NULL REFERENCES golden_source_rights_receipt(receipt_id) ON DELETE RESTRICT,
 long_rights_receipt_id UUID NOT NULL REFERENCES golden_source_rights_receipt(receipt_id) ON DELETE RESTRICT,
 short_rights_receipt_id UUID NOT NULL REFERENCES golden_source_rights_receipt(receipt_id) ON DELETE RESTRICT,
 master_rights_receipt_id UUID NOT NULL REFERENCES golden_source_rights_receipt(receipt_id) ON DELETE RESTRICT,
 margin_cost_rights_receipt_id UUID NOT NULL REFERENCES golden_source_rights_receipt(receipt_id) ON DELETE RESTRICT,
 first_known_at TIMESTAMPTZ NOT NULL,
 captured_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 valid_until TIMESTAMPTZ NOT NULL,
 retain_until DATE NOT NULL,
 CONSTRAINT uq_fno_ratio_source UNIQUE(provider_code,account_scope,ratio_side,long_key,short_key,first_known_at),
 CONSTRAINT ck_fno_ratio_pair CHECK (
    long_key<>short_key AND index_ask>=index_bid AND long_ask>=long_bid AND short_ask>=short_bid
    AND (ratio_side='CALL' AND option_kind='CE' AND long_strike<short_strike
        OR ratio_side='PUT' AND option_kind='PE' AND long_strike>short_strike)),
 CONSTRAINT ck_fno_ratio_original_time CHECK (
    index_exchange_at<=index_first_known_at
    AND long_exchange_at<=long_first_known_at
    AND short_exchange_at<=short_first_known_at
    AND index_first_known_at<=first_known_at
    AND long_first_known_at<=first_known_at
    AND short_first_known_at<=first_known_at
    AND first_known_at<=captured_at AND valid_until>first_known_at
    AND valid_until<=first_known_at+INTERVAL '180 seconds'),
 CONSTRAINT ck_fno_ratio_retention CHECK (
    retain_until >= (first_known_at AT TIME ZONE 'Asia/Kolkata')::DATE
    AND retain_until <= (first_known_at AT TIME ZONE 'Asia/Kolkata')::DATE+1)
);
CREATE INDEX ix_fno_ratio_source_key ON fno_verified_nifty_ratio_spread_source
    (account_scope,ratio_side,long_key,short_key,first_known_at DESC);
CREATE FUNCTION fno_reject_ratio_spread_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP='DELETE' AND OLD.retain_until<(now() AT TIME ZONE 'Asia/Kolkata')::DATE THEN
    RETURN OLD;
 END IF;
 RAISE EXCEPTION 'Original-time ratio research evidence immutable until retention'
    USING ERRCODE='23514';
END $$;
CREATE TRIGGER tr_fno_ratio_spread_immutable
 BEFORE UPDATE OR DELETE ON fno_verified_nifty_ratio_spread_source
 FOR EACH ROW EXECUTE FUNCTION fno_reject_ratio_spread_mutation();
-- No data seeding, no broker orders. All source use-rights default denied.

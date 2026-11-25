-- STR-198 EMPTY original-time three listed NIFTY CALL contracts:
-- BUY lower CE, SELL exactly TWO center CE lots, BUY upper CE, identical expiry and lot.
-- No synthetic IV, estimated midquote, broker order, seeded market data, or live/paper approval.
CREATE TABLE fno_verified_nifty_long_butterfly_source (
 evidence_id BIGSERIAL PRIMARY KEY,
 provider_code VARCHAR(32) NOT NULL CHECK(provider_code IN ('UPSTOX_ANALYTICS','ANGEL_ONE_SMARTAPI')),
 index_key VARCHAR(80) NOT NULL CHECK(index_key='NSE_INDEX|Nifty 50'),
 expiry DATE NOT NULL,
 lot_size INTEGER NOT NULL CHECK(lot_size BETWEEN 1 AND 100000),
 tick NUMERIC(20,6) NOT NULL CHECK(tick>0),
 index_bid NUMERIC(20,6) NOT NULL CHECK(index_bid>0),
 index_ask NUMERIC(20,6) NOT NULL CHECK(index_ask>=index_bid),
 index_exchange_at TIMESTAMPTZ NOT NULL,
 index_first_known_at TIMESTAMPTZ NOT NULL,
 lower_key VARCHAR(96) NOT NULL CHECK(lower_key ~ '^NSE_FO\|[A-Za-z0-9._-]{1,64}$'),
 lower_strike NUMERIC(20,6) NOT NULL CHECK(lower_strike>0),
 lower_listed_from DATE NOT NULL,
 lower_bid NUMERIC(20,6) NOT NULL CHECK(lower_bid>0),
 lower_ask NUMERIC(20,6) NOT NULL CHECK(lower_ask>=lower_bid),
 lower_iv_percent NUMERIC(12,6) NOT NULL CHECK(lower_iv_percent>0 AND lower_iv_percent<=500),
 lower_bid_depth_lots BIGINT NOT NULL CHECK(lower_bid_depth_lots>=1),
 lower_ask_depth_lots BIGINT NOT NULL CHECK(lower_ask_depth_lots>0),
 lower_oi_lots BIGINT NOT NULL CHECK(lower_oi_lots>0),
 lower_exchange_at TIMESTAMPTZ NOT NULL,
 lower_first_known_at TIMESTAMPTZ NOT NULL,
 lower_quote_source_sha256 CHAR(64) NOT NULL CHECK(lower_quote_source_sha256 ~ '^[a-f0-9]{64}$'),
 lower_iv_source_sha256 CHAR(64) NOT NULL CHECK(lower_iv_source_sha256 ~ '^[a-f0-9]{64}$'),
 lower_original_iv_verified BOOLEAN NOT NULL CHECK(lower_original_iv_verified),
 center_key VARCHAR(96) NOT NULL CHECK(center_key ~ '^NSE_FO\|[A-Za-z0-9._-]{1,64}$'),
 center_strike NUMERIC(20,6) NOT NULL CHECK(center_strike>0),
 center_listed_from DATE NOT NULL,
 center_bid NUMERIC(20,6) NOT NULL CHECK(center_bid>0),
 center_ask NUMERIC(20,6) NOT NULL CHECK(center_ask>=center_bid),
 center_iv_percent NUMERIC(12,6) NOT NULL CHECK(center_iv_percent>0 AND center_iv_percent<=500),
 center_bid_depth_lots BIGINT NOT NULL CHECK(center_bid_depth_lots>=2),
 center_ask_depth_lots BIGINT NOT NULL CHECK(center_ask_depth_lots>0),
 center_oi_lots BIGINT NOT NULL CHECK(center_oi_lots>0),
 center_exchange_at TIMESTAMPTZ NOT NULL,
 center_first_known_at TIMESTAMPTZ NOT NULL,
 center_quote_source_sha256 CHAR(64) NOT NULL CHECK(center_quote_source_sha256 ~ '^[a-f0-9]{64}$'),
 center_iv_source_sha256 CHAR(64) NOT NULL CHECK(center_iv_source_sha256 ~ '^[a-f0-9]{64}$'),
 center_original_iv_verified BOOLEAN NOT NULL CHECK(center_original_iv_verified),
 upper_key VARCHAR(96) NOT NULL CHECK(upper_key ~ '^NSE_FO\|[A-Za-z0-9._-]{1,64}$'),
 upper_strike NUMERIC(20,6) NOT NULL CHECK(upper_strike>0),
 upper_listed_from DATE NOT NULL,
 upper_bid NUMERIC(20,6) NOT NULL CHECK(upper_bid>0),
 upper_ask NUMERIC(20,6) NOT NULL CHECK(upper_ask>=upper_bid),
 upper_iv_percent NUMERIC(12,6) NOT NULL CHECK(upper_iv_percent>0 AND upper_iv_percent<=500),
 upper_bid_depth_lots BIGINT NOT NULL CHECK(upper_bid_depth_lots>=1),
 upper_ask_depth_lots BIGINT NOT NULL CHECK(upper_ask_depth_lots>0),
 upper_oi_lots BIGINT NOT NULL CHECK(upper_oi_lots>0),
 upper_exchange_at TIMESTAMPTZ NOT NULL,
 upper_first_known_at TIMESTAMPTZ NOT NULL,
 upper_quote_source_sha256 CHAR(64) NOT NULL CHECK(upper_quote_source_sha256 ~ '^[a-f0-9]{64}$'),
 upper_iv_source_sha256 CHAR(64) NOT NULL CHECK(upper_iv_source_sha256 ~ '^[a-f0-9]{64}$'),
 upper_original_iv_verified BOOLEAN NOT NULL CHECK(upper_original_iv_verified),
 instrument_master_verified BOOLEAN NOT NULL CHECK(instrument_master_verified),
 four_contract_fees_verified BOOLEAN NOT NULL CHECK(four_contract_fees_verified),
 two_center_short_lots_verified BOOLEAN NOT NULL CHECK(two_center_short_lots_verified),
 buy_slippage_bps INTEGER NOT NULL CHECK(buy_slippage_bps BETWEEN 0 AND 1000),
 sell_slippage_bps INTEGER NOT NULL CHECK(sell_slippage_bps BETWEEN 0 AND 1000),
 max_spread_bps INTEGER NOT NULL CHECK(max_spread_bps BETWEEN 1 AND 3000),
 min_depth_lots BIGINT NOT NULL CHECK(min_depth_lots>0),
 min_open_interest_lots BIGINT NOT NULL CHECK(min_open_interest_lots>0),
 max_wing_distance_bps INTEGER NOT NULL CHECK(max_wing_distance_bps BETWEEN 100 AND 10000),
 max_center_atm_distance_bps INTEGER NOT NULL CHECK(max_center_atm_distance_bps BETWEEN 1 AND 1000),
 max_iv_percent INTEGER NOT NULL CHECK(max_iv_percent BETWEEN 5 AND 500),
 four_contract_fees_inr NUMERIC(20,4) NOT NULL CHECK(four_contract_fees_inr>0),
 research_capital_inr NUMERIC(24,4) NOT NULL CHECK(research_capital_inr>0),
 max_debit_allocation_bps INTEGER NOT NULL CHECK(max_debit_allocation_bps BETWEEN 100 AND 10000),
 min_reward_to_risk_bps INTEGER NOT NULL CHECK(min_reward_to_risk_bps BETWEEN 100 AND 100000),
 max_iv_move_to_wing_bps INTEGER NOT NULL CHECK(max_iv_move_to_wing_bps BETWEEN 100 AND 100000),
 index_source_sha256 CHAR(64) NOT NULL CHECK(index_source_sha256 ~ '^[a-f0-9]{64}$'),
 master_source_sha256 CHAR(64) NOT NULL CHECK(master_source_sha256 ~ '^[a-f0-9]{64}$'),
 fees_source_sha256 CHAR(64) NOT NULL CHECK(fees_source_sha256 ~ '^[a-f0-9]{64}$'),
 index_rights_receipt_id UUID NOT NULL REFERENCES golden_source_rights_receipt(receipt_id) ON DELETE RESTRICT,
 chain_rights_receipt_id UUID NOT NULL REFERENCES golden_source_rights_receipt(receipt_id) ON DELETE RESTRICT,
 master_rights_receipt_id UUID NOT NULL REFERENCES golden_source_rights_receipt(receipt_id) ON DELETE RESTRICT,
 fees_rights_receipt_id UUID NOT NULL REFERENCES golden_source_rights_receipt(receipt_id) ON DELETE RESTRICT,
 lower_iv_rights_receipt_id UUID NOT NULL REFERENCES golden_source_rights_receipt(receipt_id) ON DELETE RESTRICT,
 center_iv_rights_receipt_id UUID NOT NULL REFERENCES golden_source_rights_receipt(receipt_id) ON DELETE RESTRICT,
 upper_iv_rights_receipt_id UUID NOT NULL REFERENCES golden_source_rights_receipt(receipt_id) ON DELETE RESTRICT,
 first_known_at TIMESTAMPTZ NOT NULL,
 captured_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 valid_until TIMESTAMPTZ NOT NULL,
 retain_until DATE NOT NULL,
 CONSTRAINT uq_fno_long_butterfly_evidence UNIQUE(provider_code,lower_key,center_key,upper_key,first_known_at),
 CONSTRAINT ck_fno_long_butterfly_strikes CHECK(
   lower_strike<center_strike AND center_strike<upper_strike
   AND center_strike-lower_strike=upper_strike-center_strike
   AND lower_strike<index_bid AND index_bid<upper_strike
   AND abs(center_strike-index_bid)*10000/index_bid <= max_center_atm_distance_bps
   AND lower_strike/tick=trunc(lower_strike/tick)
   AND center_strike/tick=trunc(center_strike/tick)
   AND upper_strike/tick=trunc(upper_strike/tick)),
 CONSTRAINT ck_fno_long_butterfly_distinct_keys CHECK(
   lower_key<>center_key AND center_key<>upper_key AND lower_key<>upper_key),
 CONSTRAINT ck_fno_long_butterfly_distinct_iv_rights CHECK(
   lower_iv_rights_receipt_id<>center_iv_rights_receipt_id
   AND center_iv_rights_receipt_id<>upper_iv_rights_receipt_id
   AND lower_iv_rights_receipt_id<>upper_iv_rights_receipt_id),
 CONSTRAINT ck_fno_long_butterfly_original_times CHECK(
   index_exchange_at<=index_first_known_at AND index_first_known_at<=first_known_at
   AND lower_exchange_at<=lower_first_known_at AND lower_first_known_at<=first_known_at
   AND center_exchange_at<=center_first_known_at AND center_first_known_at<=first_known_at
   AND upper_exchange_at<=upper_first_known_at AND upper_first_known_at<=first_known_at
   AND first_known_at<=captured_at AND first_known_at<valid_until
   AND valid_until<=first_known_at+INTERVAL '180 seconds'),
 CONSTRAINT ck_fno_long_butterfly_retention CHECK(
   retain_until>=(first_known_at AT TIME ZONE 'Asia/Kolkata')::DATE
   AND retain_until<=(first_known_at AT TIME ZONE 'Asia/Kolkata')::DATE+1)
);
CREATE INDEX ix_fno_long_butterfly_keys ON fno_verified_nifty_long_butterfly_source
 (lower_key,center_key,upper_key,first_known_at DESC);
CREATE FUNCTION fno_reject_long_butterfly_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP='DELETE' AND OLD.retain_until<(now() AT TIME ZONE 'Asia/Kolkata')::DATE THEN
   RETURN OLD;
 END IF;
 RAISE EXCEPTION 'Immutable original-time three-call long butterfly source evidence'
    USING ERRCODE='23514';
END $$;
CREATE TRIGGER tr_fno_long_butterfly_immutable
 BEFORE UPDATE OR DELETE ON fno_verified_nifty_long_butterfly_source
 FOR EACH ROW EXECUTE FUNCTION fno_reject_long_butterfly_mutation();

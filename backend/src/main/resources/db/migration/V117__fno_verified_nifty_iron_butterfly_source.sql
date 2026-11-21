-- STR-197: EMPTY source custody. No mock/fitted IV is entered as original market data.
-- Four original options, SAME CENTER STRIKE sold as CE+PE, with real BID and protective ASK.
-- Defined expiry payoff assumes all four fills/hedges remain intact; does not authorize orders.
CREATE TABLE fno_verified_nifty_iron_butterfly_source (
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
 long_put_key VARCHAR(96) NOT NULL CHECK(long_put_key ~ '^NSE_FO\|[A-Za-z0-9._-]{1,64}$'),
 long_put_strike NUMERIC(20,6) NOT NULL CHECK(long_put_strike>0),
 long_put_listed_from DATE NOT NULL,
 long_put_bid NUMERIC(20,6) NOT NULL CHECK(long_put_bid>0),
 long_put_ask NUMERIC(20,6) NOT NULL CHECK(long_put_ask>=long_put_bid),
 long_put_iv_percent NUMERIC(12,6) NOT NULL CHECK(long_put_iv_percent>0 AND long_put_iv_percent<=500),
 long_put_bid_depth_lots BIGINT NOT NULL CHECK(long_put_bid_depth_lots>0),
 long_put_ask_depth_lots BIGINT NOT NULL CHECK(long_put_ask_depth_lots>0),
 long_put_oi_lots BIGINT NOT NULL CHECK(long_put_oi_lots>0),
 long_put_exchange_at TIMESTAMPTZ NOT NULL,
 long_put_first_known_at TIMESTAMPTZ NOT NULL,
 long_put_quote_source_sha256 CHAR(64) NOT NULL CHECK(long_put_quote_source_sha256 ~ '^[a-f0-9]{64}$'),
 long_put_iv_source_sha256 CHAR(64) NOT NULL CHECK(long_put_iv_source_sha256 ~ '^[a-f0-9]{64}$'),
 long_put_iv_original_verified BOOLEAN NOT NULL CHECK(long_put_iv_original_verified),
 short_put_key VARCHAR(96) NOT NULL CHECK(short_put_key ~ '^NSE_FO\|[A-Za-z0-9._-]{1,64}$'),
 short_put_strike NUMERIC(20,6) NOT NULL CHECK(short_put_strike>0),
 short_put_listed_from DATE NOT NULL,
 short_put_bid NUMERIC(20,6) NOT NULL CHECK(short_put_bid>0),
 short_put_ask NUMERIC(20,6) NOT NULL CHECK(short_put_ask>=short_put_bid),
 short_put_iv_percent NUMERIC(12,6) NOT NULL CHECK(short_put_iv_percent>0 AND short_put_iv_percent<=500),
 short_put_bid_depth_lots BIGINT NOT NULL CHECK(short_put_bid_depth_lots>0),
 short_put_ask_depth_lots BIGINT NOT NULL CHECK(short_put_ask_depth_lots>0),
 short_put_oi_lots BIGINT NOT NULL CHECK(short_put_oi_lots>0),
 short_put_exchange_at TIMESTAMPTZ NOT NULL,
 short_put_first_known_at TIMESTAMPTZ NOT NULL,
 short_put_quote_source_sha256 CHAR(64) NOT NULL CHECK(short_put_quote_source_sha256 ~ '^[a-f0-9]{64}$'),
 short_put_iv_source_sha256 CHAR(64) NOT NULL CHECK(short_put_iv_source_sha256 ~ '^[a-f0-9]{64}$'),
 short_put_iv_original_verified BOOLEAN NOT NULL CHECK(short_put_iv_original_verified),
 short_call_key VARCHAR(96) NOT NULL CHECK(short_call_key ~ '^NSE_FO\|[A-Za-z0-9._-]{1,64}$'),
 short_call_strike NUMERIC(20,6) NOT NULL CHECK(short_call_strike>0),
 short_call_listed_from DATE NOT NULL,
 short_call_bid NUMERIC(20,6) NOT NULL CHECK(short_call_bid>0),
 short_call_ask NUMERIC(20,6) NOT NULL CHECK(short_call_ask>=short_call_bid),
 short_call_iv_percent NUMERIC(12,6) NOT NULL CHECK(short_call_iv_percent>0 AND short_call_iv_percent<=500),
 short_call_bid_depth_lots BIGINT NOT NULL CHECK(short_call_bid_depth_lots>0),
 short_call_ask_depth_lots BIGINT NOT NULL CHECK(short_call_ask_depth_lots>0),
 short_call_oi_lots BIGINT NOT NULL CHECK(short_call_oi_lots>0),
 short_call_exchange_at TIMESTAMPTZ NOT NULL,
 short_call_first_known_at TIMESTAMPTZ NOT NULL,
 short_call_quote_source_sha256 CHAR(64) NOT NULL CHECK(short_call_quote_source_sha256 ~ '^[a-f0-9]{64}$'),
 short_call_iv_source_sha256 CHAR(64) NOT NULL CHECK(short_call_iv_source_sha256 ~ '^[a-f0-9]{64}$'),
 short_call_iv_original_verified BOOLEAN NOT NULL CHECK(short_call_iv_original_verified),
 long_call_key VARCHAR(96) NOT NULL CHECK(long_call_key ~ '^NSE_FO\|[A-Za-z0-9._-]{1,64}$'),
 long_call_strike NUMERIC(20,6) NOT NULL CHECK(long_call_strike>0),
 long_call_listed_from DATE NOT NULL,
 long_call_bid NUMERIC(20,6) NOT NULL CHECK(long_call_bid>0),
 long_call_ask NUMERIC(20,6) NOT NULL CHECK(long_call_ask>=long_call_bid),
 long_call_iv_percent NUMERIC(12,6) NOT NULL CHECK(long_call_iv_percent>0 AND long_call_iv_percent<=500),
 long_call_bid_depth_lots BIGINT NOT NULL CHECK(long_call_bid_depth_lots>0),
 long_call_ask_depth_lots BIGINT NOT NULL CHECK(long_call_ask_depth_lots>0),
 long_call_oi_lots BIGINT NOT NULL CHECK(long_call_oi_lots>0),
 long_call_exchange_at TIMESTAMPTZ NOT NULL,
 long_call_first_known_at TIMESTAMPTZ NOT NULL,
 long_call_quote_source_sha256 CHAR(64) NOT NULL CHECK(long_call_quote_source_sha256 ~ '^[a-f0-9]{64}$'),
 long_call_iv_source_sha256 CHAR(64) NOT NULL CHECK(long_call_iv_source_sha256 ~ '^[a-f0-9]{64}$'),
 long_call_iv_original_verified BOOLEAN NOT NULL CHECK(long_call_iv_original_verified),
 instrument_master_verified BOOLEAN NOT NULL CHECK(instrument_master_verified),
 nonduplicated_four_leg_fees_verified BOOLEAN NOT NULL CHECK(nonduplicated_four_leg_fees_verified),
 buy_slippage_bps INTEGER NOT NULL CHECK(buy_slippage_bps BETWEEN 0 AND 1000),
 sell_slippage_bps INTEGER NOT NULL CHECK(sell_slippage_bps BETWEEN 0 AND 1000),
 max_spread_bps INTEGER NOT NULL CHECK(max_spread_bps BETWEEN 1 AND 3000),
 min_depth_lots BIGINT NOT NULL CHECK(min_depth_lots>0),
 min_open_interest_lots BIGINT NOT NULL CHECK(min_open_interest_lots>0),
 max_wing_distance_bps INTEGER NOT NULL CHECK(max_wing_distance_bps BETWEEN 100 AND 10000),
 max_atm_distance_bps INTEGER NOT NULL CHECK(max_atm_distance_bps BETWEEN 1 AND 2000),
 max_iv_percent INTEGER NOT NULL CHECK(max_iv_percent BETWEEN 5 AND 500),
 four_leg_fees_inr NUMERIC(20,4) NOT NULL CHECK(four_leg_fees_inr>0),
 research_capital_inr NUMERIC(24,4) NOT NULL CHECK(research_capital_inr>0),
 max_worst_expiry_loss_allocation_bps INTEGER NOT NULL CHECK(max_worst_expiry_loss_allocation_bps BETWEEN 100 AND 10000),
 min_credit_to_worst_loss_bps INTEGER NOT NULL CHECK(min_credit_to_worst_loss_bps BETWEEN 100 AND 100000),
 max_one_sigma_to_short_strike_bps INTEGER NOT NULL CHECK(max_one_sigma_to_short_strike_bps BETWEEN 100 AND 100000),
 index_source_sha256 CHAR(64) NOT NULL CHECK(index_source_sha256 ~ '^[a-f0-9]{64}$'),
 master_source_sha256 CHAR(64) NOT NULL CHECK(master_source_sha256 ~ '^[a-f0-9]{64}$'),
 fees_source_sha256 CHAR(64) NOT NULL CHECK(fees_source_sha256 ~ '^[a-f0-9]{64}$'),
 index_rights_receipt_id UUID NOT NULL REFERENCES golden_source_rights_receipt(receipt_id) ON DELETE RESTRICT,
 chain_rights_receipt_id UUID NOT NULL REFERENCES golden_source_rights_receipt(receipt_id) ON DELETE RESTRICT,
 master_rights_receipt_id UUID NOT NULL REFERENCES golden_source_rights_receipt(receipt_id) ON DELETE RESTRICT,
 fees_rights_receipt_id UUID NOT NULL REFERENCES golden_source_rights_receipt(receipt_id) ON DELETE RESTRICT,
 long_put_iv_rights_receipt_id UUID NOT NULL REFERENCES golden_source_rights_receipt(receipt_id) ON DELETE RESTRICT,
 short_put_iv_rights_receipt_id UUID NOT NULL REFERENCES golden_source_rights_receipt(receipt_id) ON DELETE RESTRICT,
 short_call_iv_rights_receipt_id UUID NOT NULL REFERENCES golden_source_rights_receipt(receipt_id) ON DELETE RESTRICT,
 long_call_iv_rights_receipt_id UUID NOT NULL REFERENCES golden_source_rights_receipt(receipt_id) ON DELETE RESTRICT,
 first_known_at TIMESTAMPTZ NOT NULL,
 captured_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 valid_until TIMESTAMPTZ NOT NULL,
 retain_until DATE NOT NULL,
 CONSTRAINT uq_fno_iron_butterfly_proof UNIQUE(provider_code,long_put_key,short_put_key,short_call_key,long_call_key,first_known_at),
 CONSTRAINT ck_fno_iron_butterfly_strikes CHECK(
     long_put_strike<short_put_strike AND short_put_strike=short_call_strike
     AND short_call_strike<long_call_strike
     AND long_put_strike/tick=trunc(long_put_strike/tick)
     AND short_put_strike/tick=trunc(short_put_strike/tick)
     AND short_call_strike/tick=trunc(short_call_strike/tick)
     AND long_call_strike/tick=trunc(long_call_strike/tick)),
 CONSTRAINT ck_fno_iron_butterfly_unique_contracts CHECK(
     long_put_key<>short_put_key AND long_put_key<>short_call_key
     AND long_put_key<>long_call_key AND short_put_key<>short_call_key
     AND short_put_key<>long_call_key AND short_call_key<>long_call_key),
 CONSTRAINT ck_fno_iron_butterfly_distinct_iv_rights CHECK(

   long_put_iv_rights_receipt_id<>short_put_iv_rights_receipt_id AND
   long_put_iv_rights_receipt_id<>short_call_iv_rights_receipt_id AND
   long_put_iv_rights_receipt_id<>long_call_iv_rights_receipt_id AND
   short_put_iv_rights_receipt_id<>short_call_iv_rights_receipt_id AND
   short_put_iv_rights_receipt_id<>long_call_iv_rights_receipt_id AND
   short_call_iv_rights_receipt_id<>long_call_iv_rights_receipt_id),
 CONSTRAINT ck_fno_iron_butterfly_original_times CHECK(
  index_exchange_at<=index_first_known_at AND index_first_known_at<=first_known_at
  AND long_put_exchange_at<=long_put_first_known_at AND long_put_first_known_at<=first_known_at
  AND short_put_exchange_at<=short_put_first_known_at AND short_put_first_known_at<=first_known_at
  AND short_call_exchange_at<=short_call_first_known_at AND short_call_first_known_at<=first_known_at
  AND long_call_exchange_at<=long_call_first_known_at AND long_call_first_known_at<=first_known_at
  AND first_known_at<=captured_at AND first_known_at<valid_until
  AND valid_until<=first_known_at+INTERVAL '180 seconds'),
 CONSTRAINT ck_fno_iron_butterfly_retention CHECK(
  retain_until>=(first_known_at AT TIME ZONE 'Asia/Kolkata')::DATE
  AND retain_until<=(first_known_at AT TIME ZONE 'Asia/Kolkata')::DATE+1)
);
CREATE INDEX ix_fno_iron_butterfly_pair ON fno_verified_nifty_iron_butterfly_source
 (long_put_key,short_put_key,short_call_key,long_call_key,first_known_at DESC);
CREATE FUNCTION fno_reject_iron_butterfly_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP='DELETE' AND OLD.retain_until<(now() AT TIME ZONE 'Asia/Kolkata')::DATE THEN
   RETURN OLD;
 END IF;
 RAISE EXCEPTION 'Immutable original-time four-leg iron condor source evidence'
     USING ERRCODE='23514';
END $$;
CREATE TRIGGER tr_fno_iron_butterfly_immutable
 BEFORE UPDATE OR DELETE ON fno_verified_nifty_iron_butterfly_source
 FOR EACH ROW EXECUTE FUNCTION fno_reject_iron_butterfly_mutation();

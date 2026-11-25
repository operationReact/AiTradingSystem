-- STR-199 EMPTY original-time NIFTY calendar proof: one sold near-expiry option
-- and one bought far-expiry option at EXACT same strike and option type.
-- No common-expiry maximum-payoff assumption or broker orders.
CREATE TABLE fno_verified_nifty_calendar_source (
 evidence_id BIGSERIAL PRIMARY KEY,
 provider_code VARCHAR(32) NOT NULL CHECK(provider_code IN ('UPSTOX_ANALYTICS','ANGEL_ONE_SMARTAPI')),
 index_key VARCHAR(80) NOT NULL CHECK(index_key='NSE_INDEX|Nifty 50'),
 option_type VARCHAR(2) NOT NULL CHECK(option_type IN ('CE','PE')),
 strike NUMERIC(20,6) NOT NULL CHECK(strike>0),
 tick NUMERIC(20,6) NOT NULL CHECK(tick>0),
 lot_size INTEGER NOT NULL CHECK(lot_size BETWEEN 1 AND 100000),
 index_bid NUMERIC(20,6) NOT NULL CHECK(index_bid>0),
 index_ask NUMERIC(20,6) NOT NULL CHECK(index_ask>=index_bid),
 index_exchange_at TIMESTAMPTZ NOT NULL,
 index_first_known_at TIMESTAMPTZ NOT NULL,
 near_key VARCHAR(96) NOT NULL CHECK(near_key ~ '^NSE_FO\|[A-Za-z0-9._-]{1,64}$'),
 near_expiry DATE NOT NULL,
 near_listed_from DATE NOT NULL,
 near_bid NUMERIC(20,6) NOT NULL CHECK(near_bid>0),
 near_ask NUMERIC(20,6) NOT NULL CHECK(near_ask>=near_bid),
 near_iv_percent NUMERIC(12,6) NOT NULL CHECK(near_iv_percent>0 AND near_iv_percent<=500),
 near_bid_depth_lots BIGINT NOT NULL CHECK(near_bid_depth_lots>0),
 near_ask_depth_lots BIGINT NOT NULL CHECK(near_ask_depth_lots>0),
 near_oi_lots BIGINT NOT NULL CHECK(near_oi_lots>0),
 near_exchange_at TIMESTAMPTZ NOT NULL,
 near_first_known_at TIMESTAMPTZ NOT NULL,
 near_quote_source_sha256 CHAR(64) NOT NULL CHECK(near_quote_source_sha256 ~ '^[a-f0-9]{64}$'),
 near_iv_source_sha256 CHAR(64) NOT NULL CHECK(near_iv_source_sha256 ~ '^[a-f0-9]{64}$'),
 near_original_iv_verified BOOLEAN NOT NULL CHECK(near_original_iv_verified),
 far_key VARCHAR(96) NOT NULL CHECK(far_key ~ '^NSE_FO\|[A-Za-z0-9._-]{1,64}$'),
 far_expiry DATE NOT NULL,
 far_listed_from DATE NOT NULL,
 far_bid NUMERIC(20,6) NOT NULL CHECK(far_bid>0),
 far_ask NUMERIC(20,6) NOT NULL CHECK(far_ask>=far_bid),
 far_iv_percent NUMERIC(12,6) NOT NULL CHECK(far_iv_percent>0 AND far_iv_percent<=500),
 far_bid_depth_lots BIGINT NOT NULL CHECK(far_bid_depth_lots>0),
 far_ask_depth_lots BIGINT NOT NULL CHECK(far_ask_depth_lots>0),
 far_oi_lots BIGINT NOT NULL CHECK(far_oi_lots>0),
 far_exchange_at TIMESTAMPTZ NOT NULL,
 far_first_known_at TIMESTAMPTZ NOT NULL,
 far_quote_source_sha256 CHAR(64) NOT NULL CHECK(far_quote_source_sha256 ~ '^[a-f0-9]{64}$'),
 far_iv_source_sha256 CHAR(64) NOT NULL CHECK(far_iv_source_sha256 ~ '^[a-f0-9]{64}$'),
 far_original_iv_verified BOOLEAN NOT NULL CHECK(far_original_iv_verified),
 instrument_master_verified BOOLEAN NOT NULL CHECK(instrument_master_verified),
 original_two_leg_fees_verified BOOLEAN NOT NULL CHECK(original_two_leg_fees_verified),
 buy_slippage_bps INTEGER NOT NULL CHECK(buy_slippage_bps BETWEEN 0 AND 1000),
 sell_slippage_bps INTEGER NOT NULL CHECK(sell_slippage_bps BETWEEN 0 AND 1000),
 max_spread_bps INTEGER NOT NULL CHECK(max_spread_bps BETWEEN 1 AND 3000),
 min_depth_lots BIGINT NOT NULL CHECK(min_depth_lots>0),
 min_oi_lots BIGINT NOT NULL CHECK(min_oi_lots>0),
 max_atm_distance_bps INTEGER NOT NULL CHECK(max_atm_distance_bps BETWEEN 1 AND 2000),
 max_iv_percent INTEGER NOT NULL CHECK(max_iv_percent BETWEEN 5 AND 500),
 two_leg_fees_inr NUMERIC(20,4) NOT NULL CHECK(two_leg_fees_inr>0),
 research_capital_inr NUMERIC(24,4) NOT NULL CHECK(research_capital_inr>0),
 max_net_debit_allocation_bps INTEGER NOT NULL CHECK(max_net_debit_allocation_bps BETWEEN 100 AND 10000),
 min_near_far_iv_ratio_bps INTEGER NOT NULL CHECK(min_near_far_iv_ratio_bps BETWEEN 100 AND 30000),
 min_near_premium_far_premium_bps INTEGER NOT NULL CHECK(min_near_premium_far_premium_bps BETWEEN 100 AND 10000),
 max_near_one_sigma_bps INTEGER NOT NULL CHECK(max_near_one_sigma_bps BETWEEN 100 AND 20000),
 index_source_sha256 CHAR(64) NOT NULL CHECK(index_source_sha256 ~ '^[a-f0-9]{64}$'),
 master_source_sha256 CHAR(64) NOT NULL CHECK(master_source_sha256 ~ '^[a-f0-9]{64}$'),
 fees_source_sha256 CHAR(64) NOT NULL CHECK(fees_source_sha256 ~ '^[a-f0-9]{64}$'),
 index_rights_receipt_id UUID NOT NULL REFERENCES golden_source_rights_receipt(receipt_id) ON DELETE RESTRICT,
 chain_rights_receipt_id UUID NOT NULL REFERENCES golden_source_rights_receipt(receipt_id) ON DELETE RESTRICT,
 master_rights_receipt_id UUID NOT NULL REFERENCES golden_source_rights_receipt(receipt_id) ON DELETE RESTRICT,
 fees_rights_receipt_id UUID NOT NULL REFERENCES golden_source_rights_receipt(receipt_id) ON DELETE RESTRICT,
 near_iv_rights_receipt_id UUID NOT NULL REFERENCES golden_source_rights_receipt(receipt_id) ON DELETE RESTRICT,
 far_iv_rights_receipt_id UUID NOT NULL REFERENCES golden_source_rights_receipt(receipt_id) ON DELETE RESTRICT,
 first_known_at TIMESTAMPTZ NOT NULL,
 captured_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 valid_until TIMESTAMPTZ NOT NULL,
 retain_until DATE NOT NULL,
 CONSTRAINT uq_calendar_evidence UNIQUE(provider_code,near_key,far_key,first_known_at),
 CONSTRAINT ck_calendar_original_contract CHECK(
   near_key<>far_key AND near_expiry<far_expiry
   AND far_expiry-near_expiry BETWEEN 7 AND 60
   AND strike/tick=trunc(strike/tick)
   AND near_iv_source_sha256<>far_iv_source_sha256
   AND near_quote_source_sha256<>far_quote_source_sha256
   AND near_iv_rights_receipt_id<>far_iv_rights_receipt_id
   AND abs(strike-index_bid)*10000/index_bid<=max_atm_distance_bps),
 CONSTRAINT ck_calendar_original_time CHECK(
   index_exchange_at<=index_first_known_at AND index_first_known_at<=first_known_at
   AND near_exchange_at<=near_first_known_at AND near_first_known_at<=first_known_at
   AND far_exchange_at<=far_first_known_at AND far_first_known_at<=first_known_at
   AND first_known_at<=captured_at AND first_known_at<valid_until
   AND valid_until<=first_known_at+INTERVAL '180 seconds'),
 CONSTRAINT ck_calendar_retention CHECK(
   retain_until>=(first_known_at AT TIME ZONE 'Asia/Kolkata')::DATE
   AND retain_until<=(first_known_at AT TIME ZONE 'Asia/Kolkata')::DATE+1)
);
CREATE INDEX ix_fno_calendar_pair ON fno_verified_nifty_calendar_source
 (near_key,far_key,first_known_at DESC);
CREATE FUNCTION fno_reject_calendar_proof_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP='DELETE' AND OLD.retain_until<(now() AT TIME ZONE 'Asia/Kolkata')::DATE THEN
   RETURN OLD;
 END IF;
 RAISE EXCEPTION 'Immutable original-time cross-expiry calendar proof' USING ERRCODE='23514';
END $$;
CREATE TRIGGER tr_fno_calendar_proof_immutable
 BEFORE UPDATE OR DELETE ON fno_verified_nifty_calendar_source
 FOR EACH ROW EXECUTE FUNCTION fno_reject_calendar_proof_mutation();

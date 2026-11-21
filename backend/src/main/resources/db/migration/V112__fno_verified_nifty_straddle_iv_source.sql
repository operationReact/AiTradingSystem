-- STR-192: empty point-in-time, independently verified two-leg implied-volatility custody.
-- This table is NOT populated by tests, broker login or synthetic market estimates.
CREATE TABLE fno_verified_nifty_straddle_iv_source (
 evidence_id BIGSERIAL PRIMARY KEY,
 provider_code VARCHAR(32) NOT NULL CHECK (provider_code IN ('UPSTOX_ANALYTICS','ANGEL_ONE_SMARTAPI')),
 index_key VARCHAR(80) NOT NULL CHECK (index_key='NSE_INDEX|Nifty 50'),
 ce_key VARCHAR(96) NOT NULL CHECK (ce_key ~ '^NSE_FO\|[A-Za-z0-9._-]{1,64}$'),
 pe_key VARCHAR(96) NOT NULL CHECK (pe_key ~ '^NSE_FO\|[A-Za-z0-9._-]{1,64}$'),
 strike NUMERIC(20,6) NOT NULL CHECK (strike>0),
 tick NUMERIC(20,6) NOT NULL CHECK (tick>0),
 listed_from DATE NOT NULL,
 expiry DATE NOT NULL,
 lot_size INTEGER NOT NULL CHECK (lot_size BETWEEN 1 AND 100000),
 index_bid NUMERIC(20,6) NOT NULL CHECK (index_bid>0),
 index_ask NUMERIC(20,6) NOT NULL CHECK (index_ask>=index_bid),
 index_exchange_at TIMESTAMPTZ NOT NULL,
 index_first_known_at TIMESTAMPTZ NOT NULL,
 ce_bid NUMERIC(20,6) NOT NULL CHECK (ce_bid>0),
 ce_ask NUMERIC(20,6) NOT NULL CHECK (ce_ask>=ce_bid),
 ce_iv_percent NUMERIC(12,6) NOT NULL CHECK (ce_iv_percent>0 AND ce_iv_percent<=500),
 ce_ask_depth_lots BIGINT NOT NULL CHECK (ce_ask_depth_lots>0),
 ce_open_interest_lots BIGINT NOT NULL CHECK (ce_open_interest_lots>0),
 ce_exchange_at TIMESTAMPTZ NOT NULL,
 ce_first_known_at TIMESTAMPTZ NOT NULL,
 pe_bid NUMERIC(20,6) NOT NULL CHECK (pe_bid>0),
 pe_ask NUMERIC(20,6) NOT NULL CHECK (pe_ask>=pe_bid),
 pe_iv_percent NUMERIC(12,6) NOT NULL CHECK (pe_iv_percent>0 AND pe_iv_percent<=500),
 pe_ask_depth_lots BIGINT NOT NULL CHECK (pe_ask_depth_lots>0),
 pe_open_interest_lots BIGINT NOT NULL CHECK (pe_open_interest_lots>0),
 pe_exchange_at TIMESTAMPTZ NOT NULL,
 pe_first_known_at TIMESTAMPTZ NOT NULL,
 buy_slippage_bps INTEGER NOT NULL CHECK (buy_slippage_bps BETWEEN 0 AND 1000),
 max_spread_bps INTEGER NOT NULL CHECK (max_spread_bps BETWEEN 1 AND 3000),
 min_depth_lots BIGINT NOT NULL CHECK (min_depth_lots>0),
 min_oi_lots BIGINT NOT NULL CHECK (min_oi_lots>0),
 max_atm_distance_bps INTEGER NOT NULL CHECK (max_atm_distance_bps BETWEEN 1 AND 2000),
 max_iv_percent INTEGER NOT NULL CHECK (max_iv_percent BETWEEN 5 AND 500),
 all_in_fees_per_lot_inr NUMERIC(20,4) NOT NULL CHECK (all_in_fees_per_lot_inr>0),
 premium_budget_inr NUMERIC(24,4) NOT NULL CHECK (premium_budget_inr>0),
 max_premium_allocation_bps INTEGER NOT NULL CHECK (max_premium_allocation_bps BETWEEN 100 AND 10000),
 min_expected_move_over_cost_bps INTEGER NOT NULL CHECK (min_expected_move_over_cost_bps BETWEEN 100 AND 100000),
 instrument_master_verified BOOLEAN NOT NULL CHECK (instrument_master_verified),
 original_iv_ce_verified BOOLEAN NOT NULL CHECK (original_iv_ce_verified),
 original_iv_pe_verified BOOLEAN NOT NULL CHECK (original_iv_pe_verified),
 index_source_sha256 CHAR(64) NOT NULL CHECK (index_source_sha256 ~ '^[a-f0-9]{64}$'),
 ce_chain_source_sha256 CHAR(64) NOT NULL CHECK (ce_chain_source_sha256 ~ '^[a-f0-9]{64}$'),
 pe_chain_source_sha256 CHAR(64) NOT NULL CHECK (pe_chain_source_sha256 ~ '^[a-f0-9]{64}$'),
 ce_iv_source_sha256 CHAR(64) NOT NULL CHECK (ce_iv_source_sha256 ~ '^[a-f0-9]{64}$'),
 pe_iv_source_sha256 CHAR(64) NOT NULL CHECK (pe_iv_source_sha256 ~ '^[a-f0-9]{64}$'),
 master_source_sha256 CHAR(64) NOT NULL CHECK (master_source_sha256 ~ '^[a-f0-9]{64}$'),
 fees_source_sha256 CHAR(64) NOT NULL CHECK (fees_source_sha256 ~ '^[a-f0-9]{64}$'),
 index_rights_receipt_id UUID NOT NULL REFERENCES golden_source_rights_receipt(receipt_id) ON DELETE RESTRICT,
 chain_rights_receipt_id UUID NOT NULL REFERENCES golden_source_rights_receipt(receipt_id) ON DELETE RESTRICT,
 master_rights_receipt_id UUID NOT NULL REFERENCES golden_source_rights_receipt(receipt_id) ON DELETE RESTRICT,
 ce_iv_rights_receipt_id UUID NOT NULL REFERENCES golden_source_rights_receipt(receipt_id) ON DELETE RESTRICT,
 pe_iv_rights_receipt_id UUID NOT NULL REFERENCES golden_source_rights_receipt(receipt_id) ON DELETE RESTRICT,
 fees_rights_receipt_id UUID NOT NULL REFERENCES golden_source_rights_receipt(receipt_id) ON DELETE RESTRICT,
 first_known_at TIMESTAMPTZ NOT NULL,
 captured_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 valid_until TIMESTAMPTZ NOT NULL,
 retain_until DATE NOT NULL,
 CONSTRAINT uq_fno_straddle_iv_source UNIQUE (provider_code,ce_key,pe_key,first_known_at),
 CONSTRAINT ck_fno_straddle_identity CHECK (ce_key<>pe_key AND strike/tick=trunc(strike/tick)
    AND ce_iv_rights_receipt_id<>pe_iv_rights_receipt_id),
 CONSTRAINT ck_fno_straddle_times CHECK (
  index_exchange_at<=index_first_known_at
  AND ce_exchange_at<=ce_first_known_at AND pe_exchange_at<=pe_first_known_at
  AND index_first_known_at<=first_known_at AND ce_first_known_at<=first_known_at
  AND pe_first_known_at<=first_known_at
  AND first_known_at<=captured_at AND first_known_at<valid_until
  AND valid_until<=first_known_at+INTERVAL '180 seconds'),
 CONSTRAINT ck_fno_straddle_retention CHECK (
  retain_until >= (first_known_at AT TIME ZONE 'Asia/Kolkata')::DATE
  AND retain_until <= (first_known_at AT TIME ZONE 'Asia/Kolkata')::DATE+1)
);
CREATE INDEX ix_fno_straddle_iv_source_pair ON fno_verified_nifty_straddle_iv_source
 (ce_key,pe_key,first_known_at DESC);
CREATE FUNCTION fno_reject_straddle_iv_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP='DELETE' AND OLD.retain_until<(now() AT TIME ZONE 'Asia/Kolkata')::DATE THEN
   RETURN OLD;
 END IF;
 RAISE EXCEPTION 'Immutable original-time straddle IV source evidence'
    USING ERRCODE='23514';
END $$;
CREATE TRIGGER tr_fno_straddle_iv_immutable
 BEFORE UPDATE OR DELETE ON fno_verified_nifty_straddle_iv_source
 FOR EACH ROW EXECUTE FUNCTION fno_reject_straddle_iv_mutation();

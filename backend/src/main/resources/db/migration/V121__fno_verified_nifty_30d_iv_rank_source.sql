-- STR-201 EMPTY original-time NIFTY 30-calendar-day ATM IV rank custody.
-- 200+ aligned OHLC candles and independently licensed historical IV, NOT
-- options-price derived estimates. No rows seeded, trading rights or orders.
CREATE TABLE fno_nifty_30d_iv_rank_history_source (
 evidence_id BIGSERIAL PRIMARY KEY,
 provider_code VARCHAR(32) NOT NULL CHECK(provider_code IN ('UPSTOX_ANALYTICS','ANGEL_ONE_SMARTAPI')),
 index_key VARCHAR(80) NOT NULL CHECK(index_key='NSE_INDEX|Nifty 50'),
 session_date DATE NOT NULL,
 index_open NUMERIC(20,6) NOT NULL CHECK(index_open>0),
 index_high NUMERIC(20,6) NOT NULL CHECK(index_high>0),
 index_low NUMERIC(20,6) NOT NULL CHECK(index_low>0),
 index_close NUMERIC(20,6) NOT NULL CHECK(index_close>0),
 index_volume BIGINT NOT NULL CHECK(index_volume>=0),
 index_exchange_close_at TIMESTAMPTZ NOT NULL,
 index_first_known_at TIMESTAMPTZ NOT NULL,
 source_iv_percent NUMERIC(12,6) NOT NULL CHECK(source_iv_percent>0 AND source_iv_percent<=500),
 constant_tenor_days INTEGER NOT NULL CHECK(constant_tenor_days=30),
 source_method VARCHAR(80) NOT NULL CHECK(source_method='LICENSED_ATM_30D_INTERPOLATION'),
 source_iv_first_known_at TIMESTAMPTZ NOT NULL,
 original_index_sha256 CHAR(64) NOT NULL CHECK(original_index_sha256 ~ '^[a-f0-9]{64}$'),
 original_iv_sha256 CHAR(64) NOT NULL CHECK(original_iv_sha256 ~ '^[a-f0-9]{64}$'),
 interpolation_source_sha256 CHAR(64) NOT NULL CHECK(interpolation_source_sha256 ~ '^[a-f0-9]{64}$'),
 original_index_verified BOOLEAN NOT NULL CHECK(original_index_verified),
 original_iv_verified BOOLEAN NOT NULL CHECK(original_iv_verified),
 source_iv_interpolation_verified BOOLEAN NOT NULL CHECK(source_iv_interpolation_verified),
 index_rights_receipt_id UUID NOT NULL REFERENCES golden_source_rights_receipt(receipt_id) ON DELETE RESTRICT,
 iv_history_rights_receipt_id UUID NOT NULL REFERENCES golden_source_rights_receipt(receipt_id) ON DELETE RESTRICT,
 normalization_rights_receipt_id UUID NOT NULL REFERENCES golden_source_rights_receipt(receipt_id) ON DELETE RESTRICT,
 first_known_at TIMESTAMPTZ NOT NULL,
 captured_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 retain_until DATE NOT NULL,
 CONSTRAINT uq_fno_rank_history_source UNIQUE(provider_code,session_date,first_known_at),
 CONSTRAINT ck_fno_rank_history_ohlc CHECK(
   index_high>=index_open AND index_high>=index_close
   AND index_low<=index_open AND index_low<=index_close
   AND index_low<=index_high),
 CONSTRAINT ck_fno_rank_history_times CHECK(
   index_exchange_close_at=(session_date::timestamp+INTERVAL '15 hours 30 minutes') AT TIME ZONE 'Asia/Kolkata'
   AND index_exchange_close_at<=index_first_known_at
   AND index_exchange_close_at<=source_iv_first_known_at
   AND index_first_known_at<=first_known_at AND source_iv_first_known_at<=first_known_at
   AND first_known_at<=captured_at),
 CONSTRAINT ck_fno_rank_history_rights_distinct CHECK(
   index_rights_receipt_id<>iv_history_rights_receipt_id
   AND index_rights_receipt_id<>normalization_rights_receipt_id
   AND iv_history_rights_receipt_id<>normalization_rights_receipt_id),
 CONSTRAINT ck_fno_rank_history_retention CHECK(
   retain_until>=(first_known_at AT TIME ZONE 'Asia/Kolkata')::DATE)
);
CREATE INDEX ix_fno_rank_hist_source ON fno_nifty_30d_iv_rank_history_source
 (provider_code,session_date DESC,first_known_at DESC);

CREATE TABLE fno_nifty_30d_iv_rank_current_source (
 evidence_id BIGSERIAL PRIMARY KEY,
 provider_code VARCHAR(32) NOT NULL CHECK(provider_code IN ('UPSTOX_ANALYTICS','ANGEL_ONE_SMARTAPI')),
 index_key VARCHAR(80) NOT NULL CHECK(index_key='NSE_INDEX|Nifty 50'),
 current_iv_percent NUMERIC(12,6) NOT NULL CHECK(current_iv_percent>0 AND current_iv_percent<=500),
 constant_tenor_days INTEGER NOT NULL CHECK(constant_tenor_days=30),
 source_method VARCHAR(80) NOT NULL CHECK(source_method='LICENSED_ATM_30D_INTERPOLATION'),
 original_iv_verified BOOLEAN NOT NULL CHECK(original_iv_verified),
 original_normalization_verified BOOLEAN NOT NULL CHECK(original_normalization_verified),
 exchange_at TIMESTAMPTZ NOT NULL,
 first_known_at TIMESTAMPTZ NOT NULL,
 captured_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 valid_until TIMESTAMPTZ NOT NULL,
 retain_until DATE NOT NULL,
 iv_quote_source_sha256 CHAR(64) NOT NULL CHECK(iv_quote_source_sha256 ~ '^[a-f0-9]{64}$'),
 normalization_source_sha256 CHAR(64) NOT NULL CHECK(normalization_source_sha256 ~ '^[a-f0-9]{64}$'),
 chain_rights_receipt_id UUID NOT NULL REFERENCES golden_source_rights_receipt(receipt_id) ON DELETE RESTRICT,
 current_iv_rights_receipt_id UUID NOT NULL REFERENCES golden_source_rights_receipt(receipt_id) ON DELETE RESTRICT,
 normalization_rights_receipt_id UUID NOT NULL REFERENCES golden_source_rights_receipt(receipt_id) ON DELETE RESTRICT,
 CONSTRAINT ck_fno_rank_current_times CHECK(
   exchange_at<=first_known_at AND first_known_at<=captured_at
   AND first_known_at<valid_until AND valid_until<=first_known_at+INTERVAL '180 seconds'),
 CONSTRAINT ck_fno_rank_current_rights_distinct CHECK(
   chain_rights_receipt_id<>current_iv_rights_receipt_id
   AND current_iv_rights_receipt_id<>normalization_rights_receipt_id
   AND chain_rights_receipt_id<>normalization_rights_receipt_id),
 CONSTRAINT ck_fno_rank_current_retention CHECK(
   retain_until>=(first_known_at AT TIME ZONE 'Asia/Kolkata')::DATE)
);
CREATE INDEX ix_fno_rank_current_source ON fno_nifty_30d_iv_rank_current_source
 (provider_code,exchange_at DESC,first_known_at DESC);

CREATE FUNCTION fno_reject_iv_rank_source_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP='DELETE' AND OLD.retain_until<(now() AT TIME ZONE 'Asia/Kolkata')::DATE THEN
   RETURN OLD;
 END IF;
 RAISE EXCEPTION 'Immutable original-time 30d NIFTY IV rank source evidence'
  USING ERRCODE='23514';
END $$;
CREATE TRIGGER tr_fno_rank_history_immutable
 BEFORE UPDATE OR DELETE ON fno_nifty_30d_iv_rank_history_source
 FOR EACH ROW EXECUTE FUNCTION fno_reject_iv_rank_source_mutation();
CREATE TRIGGER tr_fno_rank_current_immutable
 BEFORE UPDATE OR DELETE ON fno_nifty_30d_iv_rank_current_source
 FOR EACH ROW EXECUTE FUNCTION fno_reject_iv_rank_source_mutation();

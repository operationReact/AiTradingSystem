-- STR-203..206: EMPTY immutable point-in-time research evidence envelopes.
-- Each payload encodes the EXACT evaluator-specific typed input; not a synthetic
-- or broker-confirmed position by itself. Independent licensing checks fail closed.
-- Never seed fabricated original source values or enable PAPER/LIVE orders.
CREATE TABLE fno_verified_multistrategy_research_source (
 evidence_id BIGSERIAL PRIMARY KEY,
 strategy_id VARCHAR(32) NOT NULL CHECK(strategy_id IN
  ('strategy.STR-203','strategy.STR-204',
   'strategy.STR-205','strategy.STR-206')),
 provider_code VARCHAR(32) NOT NULL CHECK(provider_code IN
  ('UPSTOX_ANALYTICS','ANGEL_ONE_SMARTAPI')),
 index_key VARCHAR(80) NOT NULL CHECK(index_key='NSE_INDEX|Nifty 50'),
 original_source_payload JSONB NOT NULL CHECK(jsonb_typeof(original_source_payload)='object'),
 original_payload_sha256 CHAR(64) NOT NULL CHECK(original_payload_sha256 ~ '^[a-f0-9]{64}$'),
 original_index_source_sha256 CHAR(64) NOT NULL CHECK(original_index_source_sha256 ~ '^[a-f0-9]{64}$'),
 original_options_source_sha256 CHAR(64) NOT NULL CHECK(original_options_source_sha256 ~ '^[a-f0-9]{64}$'),
 original_history_source_sha256 CHAR(64) NOT NULL CHECK(original_history_source_sha256 ~ '^[a-f0-9]{64}$'),
 original_greeks_source_sha256 CHAR(64) NOT NULL CHECK(original_greeks_source_sha256 ~ '^[a-f0-9]{64}$'),
 original_master_source_sha256 CHAR(64) NOT NULL CHECK(original_master_source_sha256 ~ '^[a-f0-9]{64}$'),
 rights_index_receipt_id UUID NOT NULL REFERENCES golden_source_rights_receipt(receipt_id) ON DELETE RESTRICT,
 rights_options_receipt_id UUID NOT NULL REFERENCES golden_source_rights_receipt(receipt_id) ON DELETE RESTRICT,
 rights_history_receipt_id UUID NOT NULL REFERENCES golden_source_rights_receipt(receipt_id) ON DELETE RESTRICT,
 rights_greeks_receipt_id UUID NOT NULL REFERENCES golden_source_rights_receipt(receipt_id) ON DELETE RESTRICT,
 rights_master_receipt_id UUID NOT NULL REFERENCES golden_source_rights_receipt(receipt_id) ON DELETE RESTRICT,
 rights_auxiliary_receipt_id UUID NOT NULL REFERENCES golden_source_rights_receipt(receipt_id) ON DELETE RESTRICT,
 original_first_known_at TIMESTAMPTZ NOT NULL,
 source_verified BOOLEAN NOT NULL CHECK(source_verified),
 captured_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 valid_until TIMESTAMPTZ NOT NULL,
 retain_until DATE NOT NULL,
 CONSTRAINT uq_fno_multistrategy_original_evidence
  UNIQUE(strategy_id,provider_code,original_payload_sha256,original_first_known_at),
 CONSTRAINT ck_fno_multistrategy_distinct_rights CHECK(
  rights_index_receipt_id<>rights_options_receipt_id
  AND rights_index_receipt_id<>rights_history_receipt_id
  AND rights_index_receipt_id<>rights_greeks_receipt_id
  AND rights_index_receipt_id<>rights_master_receipt_id
  AND rights_index_receipt_id<>rights_auxiliary_receipt_id
  AND rights_options_receipt_id<>rights_history_receipt_id
  AND rights_options_receipt_id<>rights_greeks_receipt_id
  AND rights_options_receipt_id<>rights_master_receipt_id
  AND rights_options_receipt_id<>rights_auxiliary_receipt_id
  AND rights_history_receipt_id<>rights_greeks_receipt_id
  AND rights_history_receipt_id<>rights_master_receipt_id
  AND rights_history_receipt_id<>rights_auxiliary_receipt_id
  AND rights_greeks_receipt_id<>rights_master_receipt_id
  AND rights_greeks_receipt_id<>rights_auxiliary_receipt_id
  AND rights_master_receipt_id<>rights_auxiliary_receipt_id),
 CONSTRAINT ck_fno_multistrategy_source_sha_distinct CHECK(
  original_payload_sha256<>original_index_source_sha256
  AND original_index_source_sha256<>original_options_source_sha256
  AND original_options_source_sha256<>original_history_source_sha256
  AND original_history_source_sha256<>original_greeks_source_sha256
  AND original_greeks_source_sha256<>original_master_source_sha256),
 CONSTRAINT ck_fno_multistrategy_original_times CHECK(
   original_first_known_at<=captured_at
   AND original_first_known_at<valid_until
   AND valid_until<=original_first_known_at+INTERVAL '180 seconds'),
 CONSTRAINT ck_fno_multistrategy_retention CHECK(
   retain_until>=(original_first_known_at AT TIME ZONE 'Asia/Kolkata')::DATE)
);
CREATE INDEX ix_fno_multistrategy_original_evidence
 ON fno_verified_multistrategy_research_source
 (strategy_id,original_first_known_at DESC);
CREATE FUNCTION fno_reject_multistrategy_source_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP='DELETE' AND OLD.retain_until<(now() AT TIME ZONE 'Asia/Kolkata')::DATE THEN RETURN OLD; END IF;
 RAISE EXCEPTION 'Immutable original multi-strategy research evidence' USING ERRCODE='23514';
END $$;
CREATE TRIGGER tr_fno_multistrategy_source_immutable
 BEFORE UPDATE OR DELETE ON fno_verified_multistrategy_research_source
 FOR EACH ROW EXECUTE FUNCTION fno_reject_multistrategy_source_mutation();

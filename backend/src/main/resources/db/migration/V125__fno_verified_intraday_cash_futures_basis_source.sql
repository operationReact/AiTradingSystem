-- STR-170: EMPTY original, paired NSE_EQ + matching NSE_FO futures L1
-- source for INTRADAY as-of cash-and-carry research. V105 finalized EOD
-- source is INELIGIBLE: its quotes have aged >15s when final OHLCV is known.
-- No seeded rows, rights auto-grants, provider downloads or orders.
CREATE TABLE fno_verified_intraday_cash_futures_basis_source (
    evidence_id BIGSERIAL PRIMARY KEY,
    provider_code VARCHAR(32) NOT NULL CHECK (
        provider_code IN ('UPSTOX_V3', 'ANGEL_ONE_SMARTAPI')),
    spot_key VARCHAR(80) NOT NULL CHECK (
        spot_key ~ '^NSE_EQ\|[A-Za-z0-9._-]{1,64}$'),
    futures_key VARCHAR(80) NOT NULL CHECK (
        futures_key ~ '^NSE_FO\|[A-Za-z0-9._-]{1,64}$'),
    listed_from DATE NOT NULL,
    expiry DATE NOT NULL,
    lot_size INTEGER NOT NULL CHECK (lot_size BETWEEN 1 AND 100000),
    spot_bid NUMERIC(20,6) NOT NULL CHECK (spot_bid > 0),
    spot_ask NUMERIC(20,6) NOT NULL CHECK (spot_ask > 0),
    futures_bid NUMERIC(20,6) NOT NULL CHECK (futures_bid > 0),
    futures_ask NUMERIC(20,6) NOT NULL CHECK (futures_ask > 0),
    spot_exchange_at TIMESTAMPTZ NOT NULL,
    futures_exchange_at TIMESTAMPTZ NOT NULL,
    spot_first_observed_at TIMESTAMPTZ NOT NULL,
    futures_first_observed_at TIMESTAMPTZ NOT NULL,
    funding_annual_bps NUMERIC(18,6) NOT NULL CHECK (
        funding_annual_bps BETWEEN 0 AND 5000),
    dividend_annual_bps NUMERIC(18,6) NOT NULL CHECK (
        dividend_annual_bps BETWEEN 0 AND 10000),
    round_trip_cost_bps NUMERIC(18,6) NOT NULL CHECK (
        round_trip_cost_bps BETWEEN 1 AND 2000),
    min_net_edge_bps NUMERIC(18,6) NOT NULL CHECK (
        min_net_edge_bps BETWEEN 1 AND 2000),
    delivery_verified BOOLEAN NOT NULL CHECK (delivery_verified),
    costs_verified BOOLEAN NOT NULL CHECK (costs_verified),
    spot_source_digest CHAR(64) NOT NULL CHECK (
        spot_source_digest ~ '^[a-f0-9]{64}$'),
    futures_source_digest CHAR(64) NOT NULL CHECK (
        futures_source_digest ~ '^[a-f0-9]{64}$'),
    listing_source_digest CHAR(64) NOT NULL CHECK (
        listing_source_digest ~ '^[a-f0-9]{64}$'),
    financing_cost_source_digest CHAR(64) NOT NULL CHECK (
        financing_cost_source_digest ~ '^[a-f0-9]{64}$'),
    spot_calendar_evidence_id UUID NOT NULL REFERENCES
        golden_historical_exchange_session_evidence(evidence_id) ON DELETE RESTRICT,
    futures_calendar_evidence_id UUID NOT NULL REFERENCES
        golden_historical_exchange_session_evidence(evidence_id) ON DELETE RESTRICT,
    spot_rights_receipt_id UUID NOT NULL REFERENCES
        golden_source_rights_receipt(receipt_id) ON DELETE RESTRICT,
    futures_rights_receipt_id UUID NOT NULL REFERENCES
        golden_source_rights_receipt(receipt_id) ON DELETE RESTRICT,
    listing_rights_receipt_id UUID NOT NULL REFERENCES
        golden_source_rights_receipt(receipt_id) ON DELETE RESTRICT,
    costs_rights_receipt_id UUID NOT NULL REFERENCES
        golden_source_rights_receipt(receipt_id) ON DELETE RESTRICT,
    retain_until DATE NOT NULL,
    captured_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_fno_170_original_provider_pair_tick UNIQUE (
        provider_code,spot_key,futures_key,futures_exchange_at),
    CONSTRAINT ck_fno_170_original_contract_date CHECK (
        listed_from <= (spot_exchange_at AT TIME ZONE 'Asia/Kolkata')::DATE
        AND listed_from <= (futures_exchange_at AT TIME ZONE 'Asia/Kolkata')::DATE
        AND expiry >= (spot_exchange_at AT TIME ZONE 'Asia/Kolkata')::DATE
        AND expiry >= (futures_exchange_at AT TIME ZONE 'Asia/Kolkata')::DATE),
    CONSTRAINT ck_fno_170_original_quotes CHECK (
        spot_ask >= spot_bid AND futures_ask >= futures_bid),
    CONSTRAINT ck_fno_170_original_observed_custody CHECK (
        spot_exchange_at <= spot_first_observed_at
        AND futures_exchange_at <= futures_first_observed_at
        AND spot_first_observed_at <= captured_at
        AND futures_first_observed_at <= captured_at),
    CONSTRAINT ck_fno_170_original_retention CHECK (
        retain_until >= (spot_exchange_at AT TIME ZONE 'Asia/Kolkata')::DATE
        AND retain_until <=
            (spot_exchange_at AT TIME ZONE 'Asia/Kolkata')::DATE + 1)
);
CREATE INDEX ix_fno_170_verified_asof_pair
    ON fno_verified_intraday_cash_futures_basis_source
    (spot_key,futures_key,spot_exchange_at DESC);
CREATE INDEX ix_fno_170_retention
    ON fno_verified_intraday_cash_futures_basis_source (retain_until);

CREATE FUNCTION fno_reject_170_original_source_mutation()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' AND OLD.retain_until <
            (now() AT TIME ZONE 'Asia/Kolkata')::DATE THEN
        RETURN OLD;
    END IF;
    RAISE EXCEPTION 'STR-170 original paired source immutable before expiry'
        USING ERRCODE = '23514';
END
$$;
CREATE TRIGGER tr_fno_170_original_source_immutable
BEFORE UPDATE OR DELETE ON fno_verified_intraday_cash_futures_basis_source
FOR EACH ROW EXECUTE FUNCTION fno_reject_170_original_source_mutation();

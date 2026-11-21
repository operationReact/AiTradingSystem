-- STR-187/188: EMPTY original-time NSE single-stock ownership and options
-- custody. A connected brokerage account or entered portfolio value does NOT
-- establish settled shares or actual exchange lot deliverability.
CREATE TABLE fno_verified_equity_owned_daily_close (
    evidence_id BIGSERIAL PRIMARY KEY,
    provider_code VARCHAR(32) NOT NULL CHECK (
        provider_code IN ('UPSTOX_ANALYTICS', 'ANGEL_ONE_SMARTAPI')),
    equity_key VARCHAR(80) NOT NULL CHECK (
        equity_key ~ '^NSE_EQ\|[A-Za-z0-9._-]{1,64}$'),
    session_date DATE NOT NULL,
    close_price NUMERIC(20,6) NOT NULL CHECK (close_price > 0),
    first_available_at TIMESTAMPTZ NOT NULL,
    calendar_evidence_id UUID NOT NULL
        REFERENCES golden_historical_exchange_session_evidence(evidence_id) ON DELETE RESTRICT,
    close_rights_receipt_id UUID NOT NULL
        REFERENCES golden_source_rights_receipt(receipt_id) ON DELETE RESTRICT,
    close_source_sha256 CHAR(64) NOT NULL CHECK (
        close_source_sha256 ~ '^[a-f0-9]{64}$'),
    retain_until DATE NOT NULL,
    captured_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_fno_owned_equity_close UNIQUE (
        provider_code, equity_key, session_date),
    CONSTRAINT ck_fno_owned_equity_close_known CHECK (
        first_available_at <= captured_at),
    CONSTRAINT ck_fno_owned_equity_close_retention CHECK (
        retain_until >= session_date AND retain_until <= session_date + 180)
);

CREATE TABLE fno_verified_equity_owned_option (
    evidence_id BIGSERIAL PRIMARY KEY,
    provider_code VARCHAR(32) NOT NULL CHECK (
        provider_code IN ('UPSTOX_ANALYTICS', 'ANGEL_ONE_SMARTAPI')),
    account_scope VARCHAR(80) NOT NULL CHECK (
        account_scope ~ '^[A-Za-z0-9_-]{8,80}$'),
    equity_key VARCHAR(80) NOT NULL CHECK (
        equity_key ~ '^NSE_EQ\|[A-Za-z0-9._-]{1,64}$'),
    option_key VARCHAR(80) NOT NULL CHECK (
        option_key ~ '^NSE_FO\|[A-Za-z0-9._-]{1,64}$'),
    option_type CHAR(2) NOT NULL CHECK (option_type IN ('PE', 'CE')),
    listed_from DATE NOT NULL,
    expiry DATE NOT NULL,
    lot_size INTEGER NOT NULL CHECK (lot_size BETWEEN 1 AND 100000),
    strike NUMERIC(20,6) NOT NULL CHECK (strike > 0),
    tick_size NUMERIC(20,6) NOT NULL CHECK (tick_size > 0),
    physical_delivery_match_verified BOOLEAN NOT NULL
        CHECK (physical_delivery_match_verified),
    settled_owned_shares INTEGER NOT NULL CHECK (
        settled_owned_shares BETWEEN 1 AND 100000),
    average_acquisition_cost NUMERIC(20,6) NOT NULL CHECK (
        average_acquisition_cost > 0),
    holdings_source_observed_at TIMESTAMPTZ NOT NULL,
    settled_shares_broker_reconciled BOOLEAN NOT NULL
        CHECK (settled_shares_broker_reconciled),
    shares_deliverable_verified BOOLEAN NOT NULL
        CHECK (shares_deliverable_verified),
    ownership_pledge_lien_absent BOOLEAN NOT NULL
        CHECK (ownership_pledge_lien_absent),
    equity_price NUMERIC(20,6) NOT NULL CHECK (equity_price > 0),
    equity_exchange_at TIMESTAMPTZ NOT NULL,
    equity_first_observed_at TIMESTAMPTZ NOT NULL,
    option_bid NUMERIC(20,6) NOT NULL CHECK (option_bid > 0),
    option_ask NUMERIC(20,6) NOT NULL CHECK (option_ask >= option_bid),
    option_bid_depth_lots BIGINT NOT NULL CHECK (option_bid_depth_lots > 0),
    option_ask_depth_lots BIGINT NOT NULL CHECK (option_ask_depth_lots > 0),
    option_oi_lots BIGINT NOT NULL CHECK (option_oi_lots > 0),
    option_exchange_at TIMESTAMPTZ NOT NULL,
    option_first_observed_at TIMESTAMPTZ NOT NULL,
    hypothetical_expiry_move_bps INTEGER NOT NULL CHECK (
        hypothetical_expiry_move_bps BETWEEN 0 AND 9500),
    owned_position_trigger_bps INTEGER NOT NULL CHECK (
        owned_position_trigger_bps BETWEEN 0 AND 5000),
    execution_slippage_bps INTEGER NOT NULL CHECK (
        execution_slippage_bps BETWEEN 0 AND 1000),
    max_option_spread_bps INTEGER NOT NULL CHECK (
        max_option_spread_bps BETWEEN 1 AND 3000),
    min_option_depth_lots BIGINT NOT NULL CHECK (min_option_depth_lots > 0),
    min_option_oi_lots BIGINT NOT NULL CHECK (min_option_oi_lots > 0),
    max_strike_distance_bps INTEGER NOT NULL CHECK (
        max_strike_distance_bps BETWEEN 1 AND 2000),
    all_in_one_lot_fees_inr NUMERIC(20,4) NOT NULL CHECK (
        all_in_one_lot_fees_inr > 0),
    research_capital_inr NUMERIC(20,4) NOT NULL CHECK (
        research_capital_inr > 0),
    max_capital_allocation_bps INTEGER NOT NULL CHECK (
        max_capital_allocation_bps BETWEEN 100 AND 10000),
    min_hedge_benefit_or_net_credit_inr INTEGER NOT NULL CHECK (
        min_hedge_benefit_or_net_credit_inr > 0),
    holdings_rights_receipt_id UUID NOT NULL
        REFERENCES golden_source_rights_receipt(receipt_id) ON DELETE RESTRICT,
    equity_tick_rights_receipt_id UUID NOT NULL
        REFERENCES golden_source_rights_receipt(receipt_id) ON DELETE RESTRICT,
    option_chain_rights_receipt_id UUID NOT NULL
        REFERENCES golden_source_rights_receipt(receipt_id) ON DELETE RESTRICT,
    option_master_rights_receipt_id UUID NOT NULL
        REFERENCES golden_source_rights_receipt(receipt_id) ON DELETE RESTRICT,
    fee_budget_rights_receipt_id UUID NOT NULL
        REFERENCES golden_source_rights_receipt(receipt_id) ON DELETE RESTRICT,
    source_holdings_sha256 CHAR(64) NOT NULL
        CHECK (source_holdings_sha256 ~ '^[a-f0-9]{64}$'),
    source_equity_tick_sha256 CHAR(64) NOT NULL
        CHECK (source_equity_tick_sha256 ~ '^[a-f0-9]{64}$'),
    source_option_chain_sha256 CHAR(64) NOT NULL
        CHECK (source_option_chain_sha256 ~ '^[a-f0-9]{64}$'),
    source_master_sha256 CHAR(64) NOT NULL
        CHECK (source_master_sha256 ~ '^[a-f0-9]{64}$'),
    source_fee_budget_sha256 CHAR(64) NOT NULL
        CHECK (source_fee_budget_sha256 ~ '^[a-f0-9]{64}$'),
    valid_until TIMESTAMPTZ NOT NULL,
    retain_until DATE NOT NULL,
    captured_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_fno_owned_option_proof UNIQUE (
        provider_code, account_scope, equity_key, option_key, option_exchange_at),
    CONSTRAINT ck_fno_owned_option_lot_identity CHECK (
        settled_owned_shares = lot_size),
    CONSTRAINT ck_fno_owned_option_publication CHECK (
        equity_exchange_at <= equity_first_observed_at
        AND option_exchange_at <= option_first_observed_at
        AND equity_first_observed_at <= captured_at
        AND option_first_observed_at <= captured_at
        AND holdings_source_observed_at <= captured_at
        AND valid_until > option_first_observed_at
        AND valid_until <= option_exchange_at + INTERVAL '180 seconds'),
    CONSTRAINT ck_fno_owned_option_expiry CHECK (
        expiry >= (option_exchange_at AT TIME ZONE 'Asia/Kolkata')::DATE
        AND listed_from <= (option_exchange_at AT TIME ZONE 'Asia/Kolkata')::DATE),
    CONSTRAINT ck_fno_owned_option_retention CHECK (
        retain_until >= (option_exchange_at AT TIME ZONE 'Asia/Kolkata')::DATE
        AND retain_until <= (option_exchange_at AT TIME ZONE 'Asia/Kolkata')::DATE + 1)
);
CREATE INDEX ix_fno_owned_equity_history
    ON fno_verified_equity_owned_daily_close(equity_key, session_date DESC);
CREATE INDEX ix_fno_owned_option_account
    ON fno_verified_equity_owned_option
    (account_scope, equity_key, option_key, option_exchange_at DESC);
CREATE INDEX ix_fno_owned_equity_retention
    ON fno_verified_equity_owned_daily_close(retain_until);
CREATE INDEX ix_fno_owned_option_retention
    ON fno_verified_equity_owned_option(retain_until);

CREATE FUNCTION fno_reject_owned_equity_research_mutation() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE'
        AND OLD.retain_until < (now() AT TIME ZONE 'Asia/Kolkata')::DATE THEN
        RETURN OLD;
    END IF;
    RAISE EXCEPTION 'Owned equity and option source records are immutable'
        USING ERRCODE = '23514';
END
$$;
CREATE TRIGGER tr_fno_owned_equity_history_immutable
BEFORE UPDATE OR DELETE ON fno_verified_equity_owned_daily_close
FOR EACH ROW EXECUTE FUNCTION fno_reject_owned_equity_research_mutation();
CREATE TRIGGER tr_fno_owned_option_immutable
BEFORE UPDATE OR DELETE ON fno_verified_equity_owned_option
FOR EACH ROW EXECUTE FUNCTION fno_reject_owned_equity_research_mutation();

-- Deliberately no INSERTs, seeded holdings, funds or broker actions.

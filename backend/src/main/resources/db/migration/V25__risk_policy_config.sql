CREATE TABLE risk_policy_config (
    id UUID PRIMARY KEY,
    policy_version INTEGER NOT NULL UNIQUE,
    policy_name VARCHAR(120) NOT NULL,
    active BOOLEAN NOT NULL DEFAULT FALSE,

    risk_per_trade_percent NUMERIC(8,4) NOT NULL,
    max_daily_drawdown_percent NUMERIC(8,4) NOT NULL,
    max_open_positions INTEGER NOT NULL,
    min_reward_risk_ratio NUMERIC(10,4) NOT NULL,
    max_order_notional NUMERIC(20,2) NOT NULL,
    max_gross_exposure_percent NUMERIC(8,4) NOT NULL,
    max_symbol_exposure_percent NUMERIC(8,4) NOT NULL,
    max_trades_per_day INTEGER NOT NULL,
    signal_max_age_minutes INTEGER NOT NULL,
    market_state_max_age_seconds INTEGER NOT NULL,

    enforce_market_session BOOLEAN NOT NULL DEFAULT TRUE,
    reject_existing_symbol_position BOOLEAN NOT NULL DEFAULT TRUE,
    reject_duplicate_pending_exposure BOOLEAN NOT NULL DEFAULT TRUE,

    fee_rate NUMERIC(10,8) NOT NULL,
    slippage_rate NUMERIC(10,8) NOT NULL,
    fee_slippage_profile_version VARCHAR(40) NOT NULL,

    additional_config JSONB NOT NULL DEFAULT '{}'::jsonb,
    effective_from TIMESTAMPTZ NOT NULL,
    effective_to TIMESTAMPTZ,
    created_by VARCHAR(120) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT ck_risk_policy_version CHECK (policy_version > 0),
    CONSTRAINT ck_risk_policy_risk_per_trade
        CHECK (risk_per_trade_percent > 0 AND risk_per_trade_percent <= 5),
    CONSTRAINT ck_risk_policy_daily_drawdown
        CHECK (max_daily_drawdown_percent > 0 AND max_daily_drawdown_percent <= 20),
    CONSTRAINT ck_risk_policy_open_positions
        CHECK (max_open_positions BETWEEN 1 AND 100),
    CONSTRAINT ck_risk_policy_reward_risk
        CHECK (min_reward_risk_ratio >= 1 AND min_reward_risk_ratio <= 10),
    CONSTRAINT ck_risk_policy_order_notional
        CHECK (max_order_notional > 0),
    CONSTRAINT ck_risk_policy_gross_exposure
        CHECK (max_gross_exposure_percent > 0 AND max_gross_exposure_percent <= 100),
    CONSTRAINT ck_risk_policy_symbol_exposure
        CHECK (max_symbol_exposure_percent > 0 AND max_symbol_exposure_percent <= 100),
    CONSTRAINT ck_risk_policy_trades_day
        CHECK (max_trades_per_day BETWEEN 1 AND 1000),
    CONSTRAINT ck_risk_policy_signal_age
        CHECK (signal_max_age_minutes BETWEEN 1 AND 1440),
    CONSTRAINT ck_risk_policy_market_age
        CHECK (market_state_max_age_seconds BETWEEN 1 AND 3600),
    CONSTRAINT ck_risk_policy_effective_range
        CHECK (effective_to IS NULL OR effective_to > effective_from)
);

CREATE UNIQUE INDEX ux_risk_policy_one_active
    ON risk_policy_config ((active))
    WHERE active = TRUE;

CREATE INDEX ix_risk_policy_effective
    ON risk_policy_config (effective_from DESC, policy_version DESC);

-- Historical policy values are immutable. Activation may change only lifecycle fields.
CREATE OR REPLACE FUNCTION prevent_risk_policy_config_tampering()
RETURNS trigger AS $$
BEGIN
    IF NEW.policy_version IS DISTINCT FROM OLD.policy_version
       OR NEW.policy_name IS DISTINCT FROM OLD.policy_name
       OR NEW.risk_per_trade_percent IS DISTINCT FROM OLD.risk_per_trade_percent
       OR NEW.max_daily_drawdown_percent IS DISTINCT FROM OLD.max_daily_drawdown_percent
       OR NEW.max_open_positions IS DISTINCT FROM OLD.max_open_positions
       OR NEW.min_reward_risk_ratio IS DISTINCT FROM OLD.min_reward_risk_ratio
       OR NEW.max_order_notional IS DISTINCT FROM OLD.max_order_notional
       OR NEW.max_gross_exposure_percent IS DISTINCT FROM OLD.max_gross_exposure_percent
       OR NEW.max_symbol_exposure_percent IS DISTINCT FROM OLD.max_symbol_exposure_percent
       OR NEW.max_trades_per_day IS DISTINCT FROM OLD.max_trades_per_day
       OR NEW.signal_max_age_minutes IS DISTINCT FROM OLD.signal_max_age_minutes
       OR NEW.market_state_max_age_seconds IS DISTINCT FROM OLD.market_state_max_age_seconds
       OR NEW.enforce_market_session IS DISTINCT FROM OLD.enforce_market_session
       OR NEW.reject_existing_symbol_position IS DISTINCT FROM OLD.reject_existing_symbol_position
       OR NEW.reject_duplicate_pending_exposure IS DISTINCT FROM OLD.reject_duplicate_pending_exposure
       OR NEW.fee_rate IS DISTINCT FROM OLD.fee_rate
       OR NEW.slippage_rate IS DISTINCT FROM OLD.slippage_rate
       OR NEW.fee_slippage_profile_version IS DISTINCT FROM OLD.fee_slippage_profile_version
       OR NEW.additional_config IS DISTINCT FROM OLD.additional_config
       OR NEW.created_by IS DISTINCT FROM OLD.created_by
       OR NEW.created_at IS DISTINCT FROM OLD.created_at THEN
        RAISE EXCEPTION 'risk policy values are immutable; create a new policy version';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_risk_policy_config_immutable
BEFORE UPDATE ON risk_policy_config
FOR EACH ROW
EXECUTE FUNCTION prevent_risk_policy_config_tampering();

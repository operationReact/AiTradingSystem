CREATE TABLE risk_policy_config (
    id UUID PRIMARY KEY,
    policy_version INTEGER NOT NULL UNIQUE,
    policy_name VARCHAR(120) NOT NULL,
    active BOOLEAN NOT NULL DEFAULT FALSE,

    risk_per_trade_percent NUMERIC(10,6) NOT NULL,
    max_daily_drawdown_percent NUMERIC(10,6) NOT NULL,
    max_open_positions INTEGER NOT NULL,
    min_reward_risk_ratio NUMERIC(10,6) NOT NULL,
    max_order_notional NUMERIC(20,4) NOT NULL,
    max_gross_exposure_percent NUMERIC(10,6) NOT NULL,
    max_symbol_exposure_percent NUMERIC(10,6) NOT NULL,
    max_trades_per_day INTEGER NOT NULL,
    signal_max_age_minutes INTEGER NOT NULL,
    market_state_max_age_seconds INTEGER NOT NULL,
    enforce_market_session BOOLEAN NOT NULL,
    reject_existing_symbol_position BOOLEAN NOT NULL,
    reject_duplicate_pending_exposure BOOLEAN NOT NULL,
    fee_rate NUMERIC(10,8) NOT NULL,
    slippage_rate NUMERIC(10,8) NOT NULL,
    fee_slippage_profile_version VARCHAR(40) NOT NULL,

    additional_config JSONB NOT NULL DEFAULT '{}'::jsonb,
    effective_from TIMESTAMPTZ NOT NULL,
    effective_to TIMESTAMPTZ,
    created_by VARCHAR(120) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT ck_risk_policy_version CHECK (policy_version > 0),
    CONSTRAINT ck_risk_policy_risk_per_trade CHECK (risk_per_trade_percent > 0 AND risk_per_trade_percent <= 100),
    CONSTRAINT ck_risk_policy_daily_drawdown CHECK (max_daily_drawdown_percent > 0 AND max_daily_drawdown_percent <= 100),
    CONSTRAINT ck_risk_policy_open_positions CHECK (max_open_positions > 0),
    CONSTRAINT ck_risk_policy_rr CHECK (min_reward_risk_ratio >= 0),
    CONSTRAINT ck_risk_policy_order_notional CHECK (max_order_notional > 0),
    CONSTRAINT ck_risk_policy_gross_exposure CHECK (max_gross_exposure_percent > 0 AND max_gross_exposure_percent <= 100),
    CONSTRAINT ck_risk_policy_symbol_exposure CHECK (max_symbol_exposure_percent > 0 AND max_symbol_exposure_percent <= 100),
    CONSTRAINT ck_risk_policy_trades_per_day CHECK (max_trades_per_day > 0),
    CONSTRAINT ck_risk_policy_signal_age CHECK (signal_max_age_minutes BETWEEN 1 AND 1440),
    CONSTRAINT ck_risk_policy_market_age CHECK (market_state_max_age_seconds BETWEEN 1 AND 3600),
    CONSTRAINT ck_risk_policy_effective_range CHECK (effective_to IS NULL OR effective_to > effective_from)
);

CREATE UNIQUE INDEX ux_risk_policy_one_active
    ON risk_policy_config ((active))
    WHERE active = TRUE;

CREATE INDEX ix_risk_policy_effective
    ON risk_policy_config (effective_from DESC, policy_version DESC);

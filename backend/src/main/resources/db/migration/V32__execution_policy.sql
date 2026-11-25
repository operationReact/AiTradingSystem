-- Phase 7 / P07-W01: versioned execution policy. Policies may only tighten hard risk;
-- cross-policy comparison/enforcement belongs to the deterministic policy evaluator in P07-W03.

CREATE TABLE execution_policy (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    policy_version INTEGER NOT NULL UNIQUE,
    execution_mode VARCHAR(8) NOT NULL,
    strategy VARCHAR(80),
    trading_style VARCHAR(16) NOT NULL,
    approval_mode VARCHAR(12) NOT NULL,
    max_order_notional NUMERIC(20,2) NOT NULL,
    max_daily_trades INTEGER NOT NULL,
    max_gross_exposure NUMERIC(20,2) NOT NULL,
    max_daily_loss NUMERIC(20,2) NOT NULL,
    symbol_allowlist JSONB NOT NULL DEFAULT '[]'::jsonb,
    symbol_denylist JSONB NOT NULL DEFAULT '[]'::jsonb,
    session_start TIME,
    session_end TIME,
    active BOOLEAN NOT NULL DEFAULT FALSE,
    effective_from TIMESTAMPTZ NOT NULL,
    effective_to TIMESTAMPTZ,
    created_by VARCHAR(120) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT ck_execution_policy_version CHECK (policy_version > 0),
    CONSTRAINT ck_execution_policy_mode CHECK (execution_mode IN ('PAPER','LIVE')),
    CONSTRAINT ck_execution_policy_style CHECK (trading_style IN ('INTRADAY','SWING','ANY')),
    CONSTRAINT ck_execution_policy_approval CHECK (approval_mode IN ('MANUAL','AUTO','HYBRID')),
    CONSTRAINT ck_execution_policy_order_notional CHECK (max_order_notional > 0),
    CONSTRAINT ck_execution_policy_daily_trades CHECK (max_daily_trades > 0),
    CONSTRAINT ck_execution_policy_gross_exposure CHECK (max_gross_exposure > 0),
    CONSTRAINT ck_execution_policy_daily_loss CHECK (max_daily_loss > 0),
    CONSTRAINT ck_execution_policy_allowlist_array CHECK (jsonb_typeof(symbol_allowlist) = 'array'),
    CONSTRAINT ck_execution_policy_denylist_array CHECK (jsonb_typeof(symbol_denylist) = 'array'),
    CONSTRAINT ck_execution_policy_session_pair CHECK (
        (session_start IS NULL AND session_end IS NULL)
        OR (session_start IS NOT NULL AND session_end IS NOT NULL AND session_start < session_end)
    ),
    CONSTRAINT ck_execution_policy_effective_range CHECK (effective_to IS NULL OR effective_to > effective_from)
);

CREATE INDEX idx_execution_policy_active_scope
    ON execution_policy (execution_mode, strategy, trading_style, policy_version DESC)
    WHERE active = TRUE;

CREATE INDEX idx_execution_policy_effective
    ON execution_policy (effective_from DESC, policy_version DESC);

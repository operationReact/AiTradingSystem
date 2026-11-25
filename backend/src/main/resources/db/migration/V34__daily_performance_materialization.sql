-- Derived, rebuildable analytics. The immutable fill and cash ledgers remain authoritative.
CREATE TABLE portfolio_performance_daily (
    trade_date DATE PRIMARY KEY,
    opening_equity NUMERIC(20,2) NOT NULL,
    closing_equity NUMERIC(20,2) NOT NULL,
    capital_flow NUMERIC(20,2) NOT NULL,
    realized_pnl NUMERIC(20,2) NOT NULL,
    unrealized_pnl NUMERIC(20,2) NOT NULL,
    fees NUMERIC(20,2) NOT NULL,
    slippage NUMERIC(20,2) NOT NULL,
    gross_pnl NUMERIC(20,2) NOT NULL,
    net_pnl NUMERIC(20,2) NOT NULL,
    return_percent NUMERIC(16,6),
    max_intraday_drawdown_percent NUMERIC(16,6),
    drawdown_basis VARCHAR(40) NOT NULL,
    trade_count INTEGER NOT NULL CHECK (trade_count >= 0),
    win_count INTEGER NOT NULL CHECK (win_count >= 0),
    loss_count INTEGER NOT NULL CHECK (loss_count >= 0),
    valuation_evidence JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE strategy_performance_daily (
    trade_date DATE NOT NULL,
    strategy VARCHAR(80) NOT NULL,
    realized_pnl NUMERIC(20,2) NOT NULL,
    unrealized_pnl NUMERIC(20,2) NOT NULL,
    fees NUMERIC(20,2) NOT NULL,
    slippage NUMERIC(20,2) NOT NULL,
    gross_pnl NUMERIC(20,2) NOT NULL,
    net_pnl NUMERIC(20,2) NOT NULL,
    trade_count INTEGER NOT NULL CHECK (trade_count >= 0),
    win_count INTEGER NOT NULL CHECK (win_count >= 0),
    loss_count INTEGER NOT NULL CHECK (loss_count >= 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (trade_date, strategy)
);

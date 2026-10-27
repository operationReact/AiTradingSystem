-- Phase 5: reproducible paper-only backtest runs and immutable simulated trades.
CREATE TABLE paper_backtest_run (
    id UUID PRIMARY KEY,
    instrument_id BIGINT NOT NULL,
    exchange VARCHAR(8) NOT NULL,
    trading_symbol VARCHAR(100) NOT NULL,
    symbol_token VARCHAR(32) NOT NULL,
    catalogue_version UUID,
    interval VARCHAR(20) NOT NULL CHECK (interval IN (
        'ONE_MINUTE','THREE_MINUTE','FIVE_MINUTE','TEN_MINUTE',
        'FIFTEEN_MINUTE','THIRTY_MINUTE','ONE_HOUR','ONE_DAY'
    )),
    from_date DATE NOT NULL,
    to_date DATE NOT NULL,
    fast_period INTEGER NOT NULL CHECK (fast_period >= 2),
    slow_period INTEGER NOT NULL CHECK (slow_period > fast_period AND slow_period <= 500),
    strategy_version VARCHAR(80) NOT NULL,
    initial_capital NUMERIC(20,2) NOT NULL CHECK (initial_capital > 0),
    risk_per_trade_percent NUMERIC(8,4) NOT NULL CHECK (risk_per_trade_percent > 0 AND risk_per_trade_percent <= 5),
    fee_rate NUMERIC(10,8) NOT NULL CHECK (fee_rate >= 0 AND fee_rate <= 0.01),
    slippage_rate NUMERIC(10,8) NOT NULL CHECK (slippage_rate >= 0 AND slippage_rate <= 0.05),
    stop_buffer_percent NUMERIC(8,4) NOT NULL CHECK (stop_buffer_percent > 0 AND stop_buffer_percent <= 10),
    target_risk_reward NUMERIC(8,4) NOT NULL CHECK (target_risk_reward >= 1 AND target_risk_reward <= 5),
    candles_evaluated INTEGER NOT NULL CHECK (candles_evaluated >= 0),
    trade_count INTEGER NOT NULL CHECK (trade_count >= 0),
    winning_trades INTEGER NOT NULL CHECK (winning_trades >= 0),
    losing_trades INTEGER NOT NULL CHECK (losing_trades >= 0),
    win_rate_percent NUMERIC(10,4) NOT NULL CHECK (win_rate_percent >= 0 AND win_rate_percent <= 100),
    gross_pnl NUMERIC(20,2) NOT NULL,
    total_fees NUMERIC(20,2) NOT NULL CHECK (total_fees >= 0),
    net_pnl NUMERIC(20,2) NOT NULL,
    final_equity NUMERIC(20,2) NOT NULL CHECK (final_equity >= 0),
    total_return_percent NUMERIC(12,4) NOT NULL,
    max_drawdown_percent NUMERIC(12,4) NOT NULL CHECK (max_drawdown_percent >= 0),
    profit_factor NUMERIC(12,4),
    status VARCHAR(16) NOT NULL DEFAULT 'SUCCEEDED' CHECK (status = 'SUCCEEDED'),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_paper_backtest_dates CHECK (from_date <= to_date),
    CONSTRAINT ck_paper_backtest_trade_totals CHECK (winning_trades + losing_trades <= trade_count)
);
CREATE INDEX ix_paper_backtest_recent ON paper_backtest_run (created_at DESC, id DESC);
CREATE INDEX ix_paper_backtest_instrument ON paper_backtest_run (instrument_id, interval, created_at DESC);

CREATE TABLE paper_backtest_trade (
    run_id UUID NOT NULL REFERENCES paper_backtest_run(id) ON DELETE CASCADE,
    trade_number INTEGER NOT NULL CHECK (trade_number >= 1),
    entry_at TIMESTAMPTZ NOT NULL,
    exit_at TIMESTAMPTZ NOT NULL,
    entry_price NUMERIC(20,6) NOT NULL CHECK (entry_price > 0),
    exit_price NUMERIC(20,6) NOT NULL CHECK (exit_price > 0),
    quantity INTEGER NOT NULL CHECK (quantity > 0),
    stop_loss NUMERIC(20,6) NOT NULL CHECK (stop_loss > 0),
    target_price NUMERIC(20,6) NOT NULL CHECK (target_price > entry_price),
    gross_pnl NUMERIC(20,2) NOT NULL,
    entry_fee NUMERIC(20,2) NOT NULL CHECK (entry_fee >= 0),
    exit_fee NUMERIC(20,2) NOT NULL CHECK (exit_fee >= 0),
    net_pnl NUMERIC(20,2) NOT NULL,
    exit_reason VARCHAR(32) NOT NULL CHECK (exit_reason IN (
        'STOP_LOSS','STOP_LOSS_GAP','TAKE_PROFIT','TAKE_PROFIT_GAP','END_OF_PERIOD'
    )),
    PRIMARY KEY (run_id, trade_number),
    CONSTRAINT ck_paper_backtest_trade_dates CHECK (entry_at <= exit_at)
);
CREATE INDEX ix_paper_backtest_trade_run ON paper_backtest_trade (run_id, trade_number);
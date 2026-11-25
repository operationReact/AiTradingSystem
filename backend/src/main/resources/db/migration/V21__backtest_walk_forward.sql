CREATE TABLE backtest_walk_forward_run (
    id UUID PRIMARY KEY,
    instrument_id BIGINT NOT NULL,
    exchange VARCHAR(20) NOT NULL,
    trading_symbol VARCHAR(120) NOT NULL,
    interval VARCHAR(32) NOT NULL,
    strategy_code VARCHAR(80) NOT NULL,
    strategy_version VARCHAR(80) NOT NULL,
    from_date DATE NOT NULL,
    to_date DATE NOT NULL,
    training_window_days INTEGER NOT NULL CHECK (training_window_days > 0),
    validation_window_days INTEGER NOT NULL CHECK (validation_window_days > 0),
    step_days INTEGER NOT NULL CHECK (step_days > 0),
    status VARCHAR(20) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ NULL,
    error_message VARCHAR(500) NULL
);

CREATE INDEX idx_backtest_walk_forward_run_created
    ON backtest_walk_forward_run(created_at DESC);

CREATE TABLE backtest_walk_forward_fold (
    walk_forward_id UUID NOT NULL REFERENCES backtest_walk_forward_run(id) ON DELETE CASCADE,
    fold_number INTEGER NOT NULL CHECK (fold_number > 0),
    training_run_id UUID NOT NULL REFERENCES paper_backtest_run(id),
    validation_run_id UUID NOT NULL REFERENCES paper_backtest_run(id),
    training_from DATE NOT NULL,
    training_to DATE NOT NULL,
    validation_from DATE NOT NULL,
    validation_to DATE NOT NULL,
    training_return_percent NUMERIC(12,4) NOT NULL,
    validation_return_percent NUMERIC(12,4) NOT NULL,
    validation_max_drawdown_percent NUMERIC(12,4) NOT NULL,
    PRIMARY KEY (walk_forward_id, fold_number)
);

CREATE INDEX idx_backtest_walk_forward_fold_runs
    ON backtest_walk_forward_fold(training_run_id, validation_run_id);

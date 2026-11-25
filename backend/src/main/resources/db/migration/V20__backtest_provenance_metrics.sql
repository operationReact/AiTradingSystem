ALTER TABLE paper_backtest_run
    ADD COLUMN strategy_config_version INTEGER NULL,
    ADD COLUMN methodology_version VARCHAR(80) NOT NULL DEFAULT 'BACKTEST_V1',
    ADD COLUMN data_cutoff_at TIMESTAMPTZ NULL,
    ADD COLUMN source_candle_count INTEGER,
    ADD COLUMN benchmark_return_percent NUMERIC(12,4),
    ADD COLUMN expectancy_r NUMERIC(14,6),
    ADD COLUMN average_r NUMERIC(14,6),
    ADD COLUMN exposure_percent NUMERIC(12,4),
    ADD COLUMN turnover NUMERIC(20,2),
    ADD COLUMN max_consecutive_losses INTEGER,
    ADD COLUMN assumptions_json JSONB NOT NULL DEFAULT '{}'::jsonb;

CREATE TABLE paper_backtest_equity_point (
    run_id UUID NOT NULL REFERENCES paper_backtest_run(id) ON DELETE CASCADE,
    point_time TIMESTAMPTZ NOT NULL,
    equity NUMERIC(20,2) NOT NULL,
    cash NUMERIC(20,2) NOT NULL,
    open_value NUMERIC(20,2) NOT NULL,
    PRIMARY KEY (run_id, point_time)
);

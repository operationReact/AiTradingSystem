CREATE TABLE paper_backtest_validation (
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
    split_date DATE NOT NULL,
    out_of_sample_percent INTEGER NOT NULL CHECK (out_of_sample_percent BETWEEN 10 AND 50),
    training_run_id UUID NOT NULL REFERENCES paper_backtest_run(id),
    out_of_sample_run_id UUID NOT NULL REFERENCES paper_backtest_run(id),
    training_candles INTEGER NOT NULL CHECK (training_candles >= 0),
    out_of_sample_candles INTEGER NOT NULL CHECK (out_of_sample_candles > 0),
    training_return_percent NUMERIC(12,4) NOT NULL,
    out_of_sample_return_percent NUMERIC(12,4) NOT NULL,
    return_delta_percentage_points NUMERIC(12,4) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_backtest_validation_dates CHECK (from_date < split_date AND split_date <= to_date),
    CONSTRAINT ck_backtest_validation_distinct_runs CHECK (training_run_id <> out_of_sample_run_id)
);
CREATE INDEX ix_paper_backtest_validation_recent
    ON paper_backtest_validation (created_at DESC, id DESC);
CREATE INDEX ix_paper_backtest_validation_instrument
    ON paper_backtest_validation (instrument_id, interval, created_at DESC);

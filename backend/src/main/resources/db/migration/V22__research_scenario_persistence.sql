CREATE TABLE research_scenario_run (
    id UUID PRIMARY KEY,
    scan_run_id UUID REFERENCES research_scan_run(id),
    candidate_id BIGINT REFERENCES research_scan_candidate(id),
    instrument_id BIGINT NOT NULL,
    exchange VARCHAR(20),
    trading_symbol VARCHAR(120),
    interval VARCHAR(32) NOT NULL,
    strategy VARCHAR(80),
    strategy_config_version INTEGER,
    entry_price NUMERIC(20,6) NOT NULL,
    stop_price NUMERIC(20,6) NOT NULL,
    target_price NUMERIC(20,6) NOT NULL,
    history_from TIMESTAMPTZ NOT NULL,
    history_to TIMESTAMPTZ NOT NULL,
    data_cutoff_at TIMESTAMPTZ NOT NULL,
    simulation_count INTEGER NOT NULL,
    horizon_bars INTEGER NOT NULL,
    block_size INTEGER NOT NULL,
    seed BIGINT NOT NULL,
    slippage_bps INTEGER NOT NULL,
    round_trip_cost_bps INTEGER NOT NULL,
    methodology VARCHAR(80) NOT NULL,
    regime VARCHAR(32) NOT NULL,
    historical_samples INTEGER NOT NULL DEFAULT 0,
    eligible_block_count INTEGER NOT NULL DEFAULT 0,
    target_first_count INTEGER NOT NULL DEFAULT 0,
    stop_first_count INTEGER NOT NULL DEFAULT 0,
    neither_count INTEGER NOT NULL DEFAULT 0,
    target_first_probability NUMERIC(18,8),
    stop_first_probability NUMERIC(18,8),
    neither_probability NUMERIC(18,8),
    expected_r NUMERIC(18,8),
    p05_r NUMERIC(18,8),
    p50_r NUMERIC(18,8),
    p95_r NUMERIC(18,8),
    status VARCHAR(16) NOT NULL,
    error_code VARCHAR(80),
    error_message VARCHAR(500),
    started_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_research_scenario_status CHECK (status IN ('CREATED','RUNNING','SUCCEEDED','FAILED')),
    CONSTRAINT ck_research_scenario_prices CHECK (entry_price > 0 AND stop_price > 0 AND target_price > 0 AND stop_price < entry_price AND target_price > entry_price),
    CONSTRAINT ck_research_scenario_counts CHECK (simulation_count > 0 AND horizon_bars > 0 AND block_size > 0 AND historical_samples >= 0 AND eligible_block_count >= 0),
    CONSTRAINT ck_research_scenario_probabilities CHECK (
        (target_first_probability IS NULL OR target_first_probability BETWEEN 0 AND 1)
        AND (stop_first_probability IS NULL OR stop_first_probability BETWEEN 0 AND 1)
        AND (neither_probability IS NULL OR neither_probability BETWEEN 0 AND 1)
    ),
    CONSTRAINT ck_research_scenario_history CHECK (history_from < history_to AND data_cutoff_at >= history_to),
    CONSTRAINT ck_research_scenario_strategy_version CHECK (strategy_config_version IS NULL OR strategy_config_version > 0)
);

CREATE INDEX idx_research_scenario_candidate_created
    ON research_scenario_run(candidate_id, created_at DESC);
CREATE INDEX idx_research_scenario_instrument_interval_created
    ON research_scenario_run(instrument_id, interval, created_at DESC);
CREATE INDEX idx_research_scenario_status_created
    ON research_scenario_run(status, created_at DESC);

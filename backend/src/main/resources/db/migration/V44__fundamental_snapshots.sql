CREATE TABLE fundamental_snapshot (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    symbol VARCHAR(32) NOT NULL,
    provider VARCHAR(80) NOT NULL,
    provider_record_id VARCHAR(200) NOT NULL,
    period_end TIMESTAMPTZ NOT NULL,
    available_at TIMESTAMPTZ NOT NULL,
    metrics JSONB NOT NULL,
    content_hash VARCHAR(128) NOT NULL,
    ingested_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fundamental_snapshot_available_after_period CHECK (available_at >= period_end),
    CONSTRAINT fundamental_snapshot_metrics_object CHECK (jsonb_typeof(metrics) = 'object'),
    CONSTRAINT fundamental_snapshot_provider_record_unique UNIQUE (provider, provider_record_id)
);

CREATE INDEX idx_fundamental_snapshot_symbol_available
    ON fundamental_snapshot(symbol, available_at);
CREATE INDEX idx_fundamental_snapshot_available
    ON fundamental_snapshot(available_at, id);

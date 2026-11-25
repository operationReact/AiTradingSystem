CREATE TABLE strategy_config (
    id UUID PRIMARY KEY,
    strategy_code VARCHAR(80) NOT NULL,
    config_version INTEGER NOT NULL,
    enabled BOOLEAN NOT NULL,
    config_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    effective_from TIMESTAMPTZ NOT NULL,
    effective_to TIMESTAMPTZ NULL,
    created_by VARCHAR(120) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_strategy_config_version UNIQUE (strategy_code, config_version),
    CONSTRAINT ck_strategy_config_version_positive CHECK (config_version > 0),
    CONSTRAINT ck_strategy_config_effective_range CHECK (effective_to IS NULL OR effective_to > effective_from)
);

CREATE UNIQUE INDEX ux_strategy_config_one_active
    ON strategy_config (strategy_code)
    WHERE effective_to IS NULL;

CREATE INDEX ix_strategy_config_effective
    ON strategy_config (strategy_code, effective_from DESC, config_version DESC);

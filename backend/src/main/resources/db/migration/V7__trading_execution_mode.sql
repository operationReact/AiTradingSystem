-- Persist the single operator-selected trading mode. New installations always begin paper-only.
CREATE TABLE trading_execution_mode (
    id SMALLINT PRIMARY KEY DEFAULT 1 CHECK (id = 1),
    active_mode VARCHAR(8) NOT NULL DEFAULT 'PAPER' CHECK (active_mode IN ('PAPER', 'LIVE')),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by VARCHAR(80) NOT NULL DEFAULT 'system'
);
INSERT INTO trading_execution_mode (id, active_mode, updated_by)
VALUES (1, 'PAPER', 'system')
ON CONFLICT (id) DO NOTHING;

CREATE TABLE market_data_reconciliation_claim (
    singleton_id SMALLINT PRIMARY KEY CHECK (singleton_id = 1),
    owner_id UUID,
    claimed_until TIMESTAMPTZ,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

INSERT INTO market_data_reconciliation_claim (singleton_id) VALUES (1);

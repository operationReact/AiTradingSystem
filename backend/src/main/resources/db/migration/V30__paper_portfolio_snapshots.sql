-- Phase 6 / P06-W04: immutable PAPER portfolio valuation snapshots.

CREATE TABLE paper_portfolio_snapshot (
    id BIGSERIAL PRIMARY KEY,
    snapshot_at TIMESTAMPTZ NOT NULL UNIQUE,
    cash NUMERIC(20,2) NOT NULL,
    market_value NUMERIC(20,2) NOT NULL,
    equity NUMERIC(20,2) NOT NULL,
    realized_pnl_day NUMERIC(20,2) NOT NULL,
    unrealized_pnl NUMERIC(20,2) NOT NULL,
    gross_exposure NUMERIC(20,2) NOT NULL,
    open_positions INTEGER NOT NULL CHECK (open_positions >= 0)
);

CREATE INDEX idx_paper_portfolio_snapshot_at_desc
    ON paper_portfolio_snapshot (snapshot_at DESC);

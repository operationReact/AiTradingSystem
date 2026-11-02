-- V2-09: immutable entry-to-exit lineage for outcome learning.
-- Portfolio accounting remains authoritative; this ledger adds exact fill/order lineage for research attribution.

CREATE TABLE paper_trade_lot (
    id UUID PRIMARY KEY,
    entry_order_id UUID NOT NULL REFERENCES paper_order(id),
    entry_fill_id UUID NOT NULL UNIQUE REFERENCES paper_fill(id),
    instrument_id BIGINT NULL REFERENCES instrument_master(instrument_id),
    exchange VARCHAR(8),
    symbol VARCHAR(40) NOT NULL,
    product_type VARCHAR(12) NOT NULL,
    entry_quantity INTEGER NOT NULL,
    remaining_quantity INTEGER NOT NULL,
    entry_price NUMERIC(20,6) NOT NULL,
    entry_cost_basis NUMERIC(20,2) NOT NULL,
    entry_fee_amount NUMERIC(20,2) NOT NULL,
    entry_slippage_amount NUMERIC(20,2) NOT NULL,
    opened_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_paper_trade_lot_product_type CHECK (product_type IN ('DELIVERY','INTRADAY')),
    CONSTRAINT ck_paper_trade_lot_entry_quantity CHECK (entry_quantity > 0),
    CONSTRAINT ck_paper_trade_lot_remaining_quantity CHECK (
        remaining_quantity >= 0 AND remaining_quantity <= entry_quantity
    ),
    CONSTRAINT ck_paper_trade_lot_entry_price CHECK (entry_price > 0),
    CONSTRAINT ck_paper_trade_lot_cost_basis CHECK (entry_cost_basis > 0),
    CONSTRAINT ck_paper_trade_lot_fee CHECK (entry_fee_amount >= 0),
    CONSTRAINT ck_paper_trade_lot_slippage CHECK (entry_slippage_amount >= 0)
);

CREATE TABLE paper_trade_lot_close (
    id UUID PRIMARY KEY,
    lot_id UUID NOT NULL REFERENCES paper_trade_lot(id),
    exit_order_id UUID NOT NULL REFERENCES paper_order(id),
    exit_fill_id UUID NOT NULL REFERENCES paper_fill(id),
    allocation_sequence INTEGER NOT NULL,
    allocated_quantity INTEGER NOT NULL,
    exit_price NUMERIC(20,6) NOT NULL,
    allocated_fee_amount NUMERIC(20,2) NOT NULL,
    allocated_slippage_amount NUMERIC(20,2) NOT NULL,
    allocated_realized_pnl NUMERIC(20,2) NOT NULL,
    exit_reason VARCHAR(40),
    closed_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_paper_trade_lot_close_fill_sequence UNIQUE (exit_fill_id, allocation_sequence),
    CONSTRAINT uq_paper_trade_lot_close_lot_fill UNIQUE (lot_id, exit_fill_id),
    CONSTRAINT ck_paper_trade_lot_close_sequence CHECK (allocation_sequence > 0),
    CONSTRAINT ck_paper_trade_lot_close_quantity CHECK (allocated_quantity > 0),
    CONSTRAINT ck_paper_trade_lot_close_exit_price CHECK (exit_price > 0),
    CONSTRAINT ck_paper_trade_lot_close_fee CHECK (allocated_fee_amount >= 0),
    CONSTRAINT ck_paper_trade_lot_close_slippage CHECK (allocated_slippage_amount >= 0),
    CONSTRAINT ck_paper_trade_lot_close_reason CHECK (
        exit_reason IS NULL OR exit_reason IN (
            'CUSTOMER_ORDER','STOP_LOSS','TARGET','TRAILING_STOP','INTRADAY_CUTOFF'
        )
    )
);

CREATE INDEX idx_paper_trade_lot_open_fifo
    ON paper_trade_lot (instrument_id, symbol, product_type, opened_at, id)
    WHERE remaining_quantity > 0;
CREATE INDEX idx_paper_trade_lot_entry_order ON paper_trade_lot (entry_order_id);
CREATE INDEX idx_paper_trade_lot_close_exit_order ON paper_trade_lot_close (exit_order_id);
CREATE INDEX idx_paper_trade_lot_close_lot ON paper_trade_lot_close (lot_id, closed_at);

-- Phase 6 / P06-W02: allow deterministic multiple fills per PAPER order.
-- Existing V1 rows remain sequence 1; the execution simulator may continue to emit one fill until later work.

ALTER TABLE paper_fill
    DROP CONSTRAINT IF EXISTS paper_fill_order_id_key;

ALTER TABLE paper_fill
    ADD COLUMN fill_sequence INTEGER NOT NULL DEFAULT 1,
    ADD COLUMN execution_model_version VARCHAR(40),
    ADD COLUMN liquidity_assumption VARCHAR(40),
    ADD CONSTRAINT ck_paper_fill_sequence CHECK (fill_sequence > 0),
    ADD CONSTRAINT uq_paper_fill_order_sequence UNIQUE (order_id, fill_sequence);

CREATE INDEX idx_paper_fill_order_sequence
    ON paper_fill (order_id, fill_sequence);

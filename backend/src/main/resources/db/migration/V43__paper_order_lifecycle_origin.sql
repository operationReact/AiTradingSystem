-- V2-PAPER-07: server-authoritative execution origin and lifecycle reason.
-- Existing rows are MANUAL because prior persistence did not prove an automated origin.

ALTER TABLE paper_order
    ADD COLUMN execution_origin VARCHAR(16) NOT NULL DEFAULT 'MANUAL',
    ADD COLUMN lifecycle_reason VARCHAR(40);

ALTER TABLE paper_order
    ADD CONSTRAINT ck_paper_order_execution_origin
        CHECK (execution_origin IN ('MANUAL', 'AUTOMATED')),
    ADD CONSTRAINT ck_paper_order_lifecycle_reason
        CHECK (
            lifecycle_reason IS NULL OR lifecycle_reason IN (
                'CUSTOMER_ORDER',
                'STRATEGY_SIGNAL',
                'STOP_LOSS',
                'TARGET',
                'TRAILING_STOP',
                'INTRADAY_CUTOFF'
            )
        );

CREATE INDEX idx_paper_order_lifecycle
    ON paper_order (execution_origin, lifecycle_reason, created_at DESC);

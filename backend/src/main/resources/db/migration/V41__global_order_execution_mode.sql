-- Add the user-selected order execution style to the existing single global trading authority row.
-- PAPER/LIVE remains in active_mode; MANUAL/AUTOMATED is an independent control.
ALTER TABLE trading_execution_mode
    ADD COLUMN order_execution_mode VARCHAR(12) NOT NULL DEFAULT 'MANUAL'
        CHECK (order_execution_mode IN ('MANUAL', 'AUTOMATED'));

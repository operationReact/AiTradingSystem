-- Separate where orders execute (PAPER/LIVE) from how orders are initiated (MANUAL/AUTOMATED).
-- Existing installations remain PAPER + MANUAL by default. LIVE continues to be fail-closed.
ALTER TABLE trading_execution_mode
    ADD COLUMN order_execution_mode VARCHAR(12) NOT NULL DEFAULT 'MANUAL'
        CHECK (order_execution_mode IN ('MANUAL', 'AUTOMATED'));

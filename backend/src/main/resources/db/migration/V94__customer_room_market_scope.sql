-- Explicit customer room market boundaries. No feature/rights flags are changed.
-- Existing PAPER rooms had both cash orders and an unconditional F&O panel:
-- keep their historical visibility and exit paths under ALL.
ALTER TABLE customer_trading_room
    ADD COLUMN market_scope VARCHAR(8) NOT NULL DEFAULT 'EQUITY';

UPDATE customer_trading_room
   SET market_scope = 'ALL'
 WHERE mode = 'PAPER';

ALTER TABLE customer_trading_room
    ADD CONSTRAINT ck_customer_trading_room_market_scope
    CHECK (market_scope IN ('EQUITY', 'FNO', 'ALL'));

-- No LIVE derivatives until separately approved and safety-gated.
ALTER TABLE customer_trading_room
    ADD CONSTRAINT ck_customer_room_live_equity_only
    CHECK (mode = 'PAPER' OR market_scope = 'EQUITY');

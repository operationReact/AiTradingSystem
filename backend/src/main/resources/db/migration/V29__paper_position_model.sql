-- Phase 6 / P06-W03: explicit PAPER position identity and accounting fields.

ALTER TABLE paper_position
    ADD COLUMN instrument_id BIGINT NULL REFERENCES instrument_master(instrument_id),
    ADD COLUMN exchange VARCHAR(8),
    ADD COLUMN product_type VARCHAR(12) NOT NULL DEFAULT 'DELIVERY',
    ADD COLUMN average_price NUMERIC(20,6),
    ADD COLUMN realized_pnl NUMERIC(20,2) NOT NULL DEFAULT 0;

UPDATE paper_position
   SET average_price = ROUND(cost_basis / quantity, 6)
 WHERE average_price IS NULL;

WITH active_symbol AS (
    SELECT im.trading_symbol,
           MIN(im.instrument_id) AS instrument_id,
           MIN(im.exchange) AS exchange
      FROM instrument_master im
      JOIN instrument_catalogue_state s ON s.active_version = im.catalogue_version
     WHERE im.active = TRUE
     GROUP BY im.trading_symbol
    HAVING COUNT(*) = 1
)
UPDATE paper_position p
   SET instrument_id = a.instrument_id,
       exchange = a.exchange
  FROM active_symbol a
 WHERE p.instrument_id IS NULL
   AND upper(p.symbol) = upper(a.trading_symbol);

ALTER TABLE paper_position
    ADD CONSTRAINT ck_paper_position_product_type CHECK (product_type IN ('DELIVERY','INTRADAY')),
    ADD CONSTRAINT ck_paper_position_average_price CHECK (average_price IS NULL OR average_price > 0);

-- Replace the legacy symbol-only key so delivery and intraday positions for the same instrument
-- can coexist. Legacy/synthetic rows still have a stable symbol+product identity.
ALTER TABLE paper_position DROP CONSTRAINT IF EXISTS paper_position_pkey;
ALTER TABLE paper_position
    ADD CONSTRAINT uq_paper_position_symbol_product UNIQUE (symbol, product_type);

CREATE UNIQUE INDEX uq_paper_position_instrument_product
    ON paper_position (instrument_id, product_type)
    WHERE instrument_id IS NOT NULL;

-- Phase 6 / P06-W01: evolve the existing PAPER order row into an explicit order model.
-- Keep legacy rows readable and keep the current /paper-trades/simulate compatibility path working.

ALTER TABLE paper_order
    ADD COLUMN instrument_id BIGINT NULL REFERENCES instrument_master(instrument_id),
    ADD COLUMN exchange VARCHAR(8),
    ADD COLUMN order_type VARCHAR(12) NOT NULL DEFAULT 'MARKET',
    ADD COLUMN limit_price NUMERIC(20,6),
    ADD COLUMN product_type VARCHAR(12) NOT NULL DEFAULT 'DELIVERY',
    ADD COLUMN strategy VARCHAR(80),
    ADD COLUMN candidate_id BIGINT NULL REFERENCES research_scan_candidate(id),
    ADD COLUMN submitted_quantity INTEGER,
    ADD COLUMN filled_quantity INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN average_fill_price NUMERIC(20,6);

-- Backfill explicit quantity/fill state for the existing all-or-nothing simulator.
UPDATE paper_order
   SET submitted_quantity = quantity
 WHERE submitted_quantity IS NULL;

UPDATE paper_order o
   SET filled_quantity = o.quantity,
       average_fill_price = f.fill_price
  FROM paper_fill f
 WHERE f.order_id = o.id
   AND o.status = 'FILLED';

ALTER TABLE paper_order
    ALTER COLUMN submitted_quantity SET NOT NULL,
    ADD CONSTRAINT ck_paper_order_order_type
        CHECK (order_type IN ('MARKET','LIMIT')),
    ADD CONSTRAINT ck_paper_order_limit_price
        CHECK (limit_price IS NULL OR limit_price > 0),
    ADD CONSTRAINT ck_paper_order_limit_requires_price
        CHECK (order_type <> 'LIMIT' OR limit_price IS NOT NULL),
    ADD CONSTRAINT ck_paper_order_product_type
        CHECK (product_type IN ('DELIVERY','INTRADAY')),
    ADD CONSTRAINT ck_paper_order_submitted_quantity
        CHECK (submitted_quantity > 0),
    ADD CONSTRAINT ck_paper_order_filled_quantity
        CHECK (filled_quantity >= 0 AND filled_quantity <= submitted_quantity),
    ADD CONSTRAINT ck_paper_order_average_fill_price
        CHECK (average_fill_price IS NULL OR average_fill_price > 0);

-- Deterministic legacy identity backfill only from the currently active catalogue and only
-- when exactly one active-catalogue instrument has the stored trading symbol.
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
UPDATE paper_order o
   SET instrument_id = a.instrument_id,
       exchange = a.exchange
  FROM active_symbol a
 WHERE o.instrument_id IS NULL
   AND upper(o.symbol) = upper(a.trading_symbol);

ALTER TABLE paper_order DROP CONSTRAINT IF EXISTS paper_order_status_check;
ALTER TABLE paper_order
    ADD CONSTRAINT paper_order_status_check CHECK (status IN (
        'NEW','RISK_APPROVED','APPROVAL_PENDING','READY','PARTIALLY_FILLED','FILLED',
        'REJECTED','CANCELLED','EXPIRED','FAILED'
    ));

CREATE INDEX idx_paper_order_instrument_created_at
    ON paper_order (instrument_id, created_at DESC)
    WHERE instrument_id IS NOT NULL;
CREATE INDEX idx_paper_order_candidate
    ON paper_order (candidate_id)
    WHERE candidate_id IS NOT NULL;

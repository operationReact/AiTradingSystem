-- Markets board: market-cap segments and broad-market sectors from official Nifty index lists.
--
-- 1. V49 only allowed four archive families, so the already-configured NIFTY 500 constituent
--    download (OfficialMarketSourceFamily.NIFTY500_CONSTITUENTS) could never be persisted.
--    Allow it, plus the official cap-segment lists used to classify large/mid/small/micro caps:
--      NIFTY 100            -> large cap   (top 100 by full market capitalisation)
--      NIFTY MIDCAP 150     -> mid cap     (ranks 101-250)
--      NIFTY SMALLCAP 250   -> small cap   (ranks 251-500)
--      NIFTY MICROCAP 250   -> micro cap   (ranks 501-750)
-- 2. Membership rows are materialised into market_universe_member by the ingestion service,
--    replacing a universe atomically. No member is invented here: until an official list has
--    been ingested, the customer board reports that segment as unavailable.
ALTER TABLE official_market_source_snapshot
    DROP CONSTRAINT IF EXISTS official_market_source_snapshot_source_family_check;

ALTER TABLE official_market_source_snapshot
    ADD CONSTRAINT ck_official_source_family CHECK (source_family IN (
        'NIFTY50_CONSTITUENTS',
        'NIFTY500_CONSTITUENTS',
        'NIFTY100_CONSTITUENTS',
        'NIFTY_MIDCAP150_CONSTITUENTS',
        'NIFTY_SMALLCAP250_CONSTITUENTS',
        'NIFTY_MICROCAP250_CONSTITUENTS',
        'NIFTY50_HISTORY',
        'INDIA_VIX_HISTORY',
        'SECTOR_INDEX_HISTORY'
    ));

CREATE INDEX IF NOT EXISTS ix_market_universe_member_symbol
    ON market_universe_member (trading_symbol, universe_code);

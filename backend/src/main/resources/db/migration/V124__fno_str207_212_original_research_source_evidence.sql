-- STR-207..212 EMPTY research-source support on V123 immutable provenance table.
-- No rows inserted. Preserve the 203..206 original source integrity and rights.
ALTER TABLE fno_verified_multistrategy_research_source
 DROP CONSTRAINT IF EXISTS fno_verified_multistrategy_research_source_strategy_id_check;
ALTER TABLE fno_verified_multistrategy_research_source
 ADD CONSTRAINT ck_fno_multistrategy_203_212_id
 CHECK (strategy_id IN (
  'strategy.STR-203','strategy.STR-204','strategy.STR-205','strategy.STR-206',
  'strategy.STR-207','strategy.STR-208','strategy.STR-209',
  'strategy.STR-210','strategy.STR-211','strategy.STR-212'));
-- Strategy-specific typed validation and exact source-proof rights stay mandatory.
-- No synthetic prices, broker positions, expiry cutoff, fills, or order rights.

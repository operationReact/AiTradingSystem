-- Upstox coverage / gap audit: read-only PostgreSQL schema preflight.
-- Run against an authorized read-only connection; no source API requests or writes.
-- This deliberately does NOT count receipts as verified candles.
-- If any required relation/column is missing, mark coverage UNKNOWN and stop.
-- Inspect source-specific golden_source* AND golden_dataset_backfill* schema before implementing a persisted query.
-- The latter holds job/chunk checkpoints in the current operations PR; never assume they are deployed.
SELECT table_schema, table_name, column_name, data_type, is_nullable
FROM information_schema.columns
WHERE table_schema NOT IN ('pg_catalog', 'information_schema')
  AND (table_name LIKE 'golden_source%' OR table_name LIKE 'golden_dataset_backfill%'
       OR table_name IN ('golden_data_provider', 'golden_dataset_provider_cooldown'))
ORDER BY table_schema, table_name, ordinal_position;

-- Enumerate primary/unique constraints to identify stable receipt deduplication keys.
SELECT tc.table_schema, tc.table_name, tc.constraint_name, tc.constraint_type,
       kcu.column_name, kcu.ordinal_position
FROM information_schema.table_constraints tc
JOIN information_schema.key_column_usage kcu
  ON tc.constraint_catalog = kcu.constraint_catalog
 AND tc.constraint_schema = kcu.constraint_schema
 AND tc.constraint_name = kcu.constraint_name
WHERE tc.table_schema NOT IN ('pg_catalog', 'information_schema')
  AND tc.(table_name LIKE 'golden_source%' OR table_name LIKE 'golden_dataset_backfill%'
       OR table_name IN ('golden_data_provider', 'golden_dataset_provider_cooldown'))
  AND tc.constraint_type IN ('PRIMARY KEY', 'UNIQUE')
ORDER BY tc.table_schema, tc.table_name, tc.constraint_name, kcu.ordinal_position;

-- Inspect foreign keys and their ordered columns, including provider/job/chunk linkage.
-- A matching table name is NOT evidence that rows exist or are licensed.
SELECT tc.table_schema, tc.table_name, tc.constraint_name,
       kcu.column_name AS referencing_column, kcu.ordinal_position,
       ccu.table_schema AS referenced_schema, ccu.table_name AS referenced_table,
       ccu.column_name AS referenced_column
FROM information_schema.table_constraints tc
JOIN information_schema.key_column_usage kcu
  ON tc.constraint_catalog = kcu.constraint_catalog
 AND tc.constraint_schema = kcu.constraint_schema
 AND tc.constraint_name = kcu.constraint_name
JOIN information_schema.constraint_column_usage ccu
  ON tc.constraint_catalog = ccu.constraint_catalog
 AND tc.constraint_schema = ccu.constraint_schema
 AND tc.constraint_name = ccu.constraint_name
WHERE tc.constraint_type = 'FOREIGN KEY'
  AND tc.table_schema NOT IN ('pg_catalog', 'information_schema')
  AND (tc.table_name LIKE 'golden_source%' OR tc.table_name LIKE 'golden_dataset_backfill%')
ORDER BY tc.table_schema, tc.table_name, tc.constraint_name, kcu.ordinal_position;

-- Preflight checks before a follow-on production query:
-- 1. Find the actual provider/source discriminator and verify UPSTOX only.
-- 2. Find durable receipt ID and partition key (listing episode, interval, UTC window).
-- 3. Identify persisted attempt, outcome, retry-after, rights and available-at columns.
-- 4. Count DISTINCT eligible receipt IDs, not retry events or GitHub PRs.
-- 5. Use a stable bounded ORDER BY with unique tie-breaker for pagination.
-- 6. Return UNKNOWN, not zero, if tables, rights or runtime evidence are absent.
-- 7. Never derive expected candle totals without an exchange calendar and fixed target.

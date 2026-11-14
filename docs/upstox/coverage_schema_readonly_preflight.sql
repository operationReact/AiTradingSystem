-- Upstox coverage / gap audit: read-only PostgreSQL schema preflight.
-- Run against an authorized read-only connection; no source API requests or writes.
-- This deliberately does NOT count receipts as verified candles.
-- If any required relation/column is missing, mark coverage UNKNOWN and stop.
-- Inspect source-specific golden_source* schema before implementing a persisted query.
SELECT table_schema, table_name, column_name, data_type, is_nullable
FROM information_schema.columns
WHERE table_schema NOT IN ('pg_catalog', 'information_schema')
  AND table_name LIKE 'golden_source%'
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
  AND tc.table_name LIKE 'golden_source%'
  AND tc.constraint_type IN ('PRIMARY KEY', 'UNIQUE')
ORDER BY tc.table_schema, tc.table_name, tc.constraint_name, kcu.ordinal_position;

-- Preflight checks before a follow-on production query:
-- 1. Find the actual provider/source discriminator and verify UPSTOX only.
-- 2. Find durable receipt ID and partition key (listing episode, interval, UTC window).
-- 3. Identify persisted attempt, outcome, retry-after, rights and available-at columns.
-- 4. Count DISTINCT eligible receipt IDs, not retry events or GitHub PRs.
-- 5. Use a stable bounded ORDER BY with unique tie-breaker for pagination.
-- 6. Return UNKNOWN, not zero, if tables, rights or runtime evidence are absent.
-- 7. Never derive expected candle totals without an exchange calendar and fixed target.

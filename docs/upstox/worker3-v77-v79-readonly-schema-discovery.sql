-- Worker 3 / #1012 / #1074. Read-only V77-V79 schema/rights discovery.
-- Run only with an approved read-only production DB role. No credentials in output.
-- Purpose: distinguish absent schema, absent provider, missing rights and empty jobs
-- before running any partition acceptance query. No writes or API calls.
BEGIN TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY;
SELECT current_setting('transaction_read_only') AS transaction_read_only,
       transaction_timestamp() AS observed_at;
WITH expected(relation_name) AS (
 VALUES ('golden_data_provider'), ('golden_source_rights_receipt'),
        ('golden_dataset_backfill_job'), ('golden_dataset_backfill_chunk')
)
SELECT e.relation_name,
       CASE WHEN to_regclass('public.' || e.relation_name) IS NULL
            THEN 'SCHEMA_MISSING' ELSE 'PRESENT' END AS schema_state
FROM expected e ORDER BY e.relation_name;
-- Inspect actual deployed column names and types; do not assume migration shape.
SELECT table_name, ordinal_position, column_name, data_type, is_nullable
FROM information_schema.columns
WHERE table_schema='public'
  AND table_name IN ('golden_data_provider','golden_source_rights_receipt',
                     'golden_dataset_backfill_job','golden_dataset_backfill_chunk')
ORDER BY table_name, ordinal_position;
-- Migration evidence is separate from table presence. Fail closed without
-- assuming Flyway history is in public or even installed. This query never
-- references an absent relation and therefore cannot abort the audit.
SELECT n.nspname AS history_schema, c.relname AS history_table,
       CASE WHEN c.oid IS NULL THEN 'MIGRATION_HISTORY_MISSING'
            ELSE 'MIGRATION_HISTORY_PRESENT_REQUIRES_REVIEW' END AS migration_state
FROM (SELECT 1) anchor
LEFT JOIN pg_catalog.pg_class c
  ON c.relname = 'flyway_schema_history' AND c.relkind IN ('r','p')
LEFT JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace;
-- Only after reviewing the actual history schema, separately inspect
-- V77-V79 success rows using the approved read-only role. Absence or failure
-- of any migration means UNKNOWN, never VERIFIED. Do not infer migration
-- success from table presence or from this preflight alone.
-- STOP if any expected table or migration is absent/failed.
-- Rights review must confirm domain, permitted use and valid-at timestamps
-- before counting any status=VERIFIED partition. A job count is not coverage.
-- Persisted verified partition count, target denominator and percentage
-- remain UNKNOWN until a reviewed, deduplicated rights-aware join is run.
ROLLBACK;

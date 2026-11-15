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
-- Migration evidence is separate from table presence; output is metadata only.
SELECT installed_rank, version, description, success
FROM flyway_schema_history
WHERE version IN ('77','78','79')
ORDER BY installed_rank;
-- STOP if any expected table or migration is absent/failed.
-- Rights review must confirm domain, permitted use and valid-at timestamps
-- before counting any status=VERIFIED partition. A job count is not coverage.
-- Persisted verified partition count, target denominator and percentage
-- remain UNKNOWN until a reviewed, deduplicated rights-aware join is run.
ROLLBACK;

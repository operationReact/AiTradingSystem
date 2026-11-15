-- Worker 3: V77-V79 deployed schema preflight. Metadata ONLY; not a coverage receipt.
-- Execute using approved read-only PostgreSQL access. No source/API calls or DDL.
-- Fail closed: any missing relation/column, absent rights evidence, or missing
-- manifest means UNKNOWN acceptance; never translate to 0% market coverage.
BEGIN TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY;
SELECT current_setting('transaction_read_only') AS read_only,
       transaction_timestamp() AS observed_at;
WITH expected(relation_name, column_name) AS (
  VALUES
    ('golden_data_provider','provider_id'),
    ('golden_data_provider','provider_code'),
    ('golden_source_rights_receipt','provider_id'),
    ('golden_dataset_backfill_job','provider_id'),
    ('golden_dataset_backfill_job','job_id'),
    ('golden_dataset_backfill_chunk','job_id'),
    ('golden_dataset_backfill_chunk','status'),
    ('golden_dataset_backfill_chunk','attempts'),
    ('golden_dataset_backfill_chunk','valid_records'),
    ('golden_dataset_backfill_chunk','updated_at')
), actual AS (
  SELECT table_name, column_name
  FROM information_schema.columns
  WHERE table_schema = current_schema()
)
SELECT e.relation_name, e.column_name,
       CASE WHEN a.column_name IS NULL THEN 'MISSING_BLOCKER'
            ELSE 'PRESENT' END AS deployed_schema_evidence
FROM expected e
LEFT JOIN actual a ON a.table_name=e.relation_name
                  AND a.column_name=e.column_name
ORDER BY e.relation_name,e.column_name;
-- Aggregate machine-readable gate: missing required columns block the coverage audit.
-- The summary is schema evidence only; it cannot authorize data use.
WITH expected(relation_name, column_name) AS (
  VALUES ('golden_data_provider','provider_id'),
         ('golden_data_provider','provider_code'),
         ('golden_source_rights_receipt','provider_id'),
         ('golden_dataset_backfill_job','provider_id'),
         ('golden_dataset_backfill_job','job_id'),
         ('golden_dataset_backfill_chunk','job_id'),
         ('golden_dataset_backfill_chunk','status'),
         ('golden_dataset_backfill_chunk','attempts'),
         ('golden_dataset_backfill_chunk','valid_records'),
         ('golden_dataset_backfill_chunk','updated_at')
), missing AS (
  SELECT e.relation_name, e.column_name FROM expected e
  LEFT JOIN information_schema.columns a
    ON a.table_schema = current_schema()
   AND a.table_name = e.relation_name AND a.column_name = e.column_name
  WHERE a.column_name IS NULL
)
SELECT CASE WHEN count(*) = 0 THEN 'SCHEMA_COLUMNS_PRESENT_RIGHTS_UNVERIFIED'
            ELSE 'SCHEMA_BLOCKED' END AS preflight_status,
       count(*) AS missing_required_columns
FROM missing;
-- Verify Flyway version metadata separately; never assume a successful migration
-- merely because a similarly named table exists. Do not query rights contents
-- until schema, approved domain/use and time-valid receipt columns are reviewed.
-- Report the observed schema as a prerequisite, NOT an authorized partition count.
ROLLBACK;

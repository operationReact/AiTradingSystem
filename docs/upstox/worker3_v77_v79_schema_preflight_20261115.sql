-- Worker 3: read-only V77-V79 deployed schema preflight; never coverage acceptance.
-- Requires an approved read-only PostgreSQL session. No data writes, API or secrets.
-- Explicit schema avoids search_path/current_schema ambiguity.
BEGIN TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY;
SELECT current_setting('transaction_read_only') AS read_only, transaction_timestamp() AS observed_at;
WITH expected(relation_name,column_name) AS (
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
), evidence AS (
 SELECT e.relation_name,e.column_name,
        CASE WHEN c.column_name IS NULL THEN 'MISSING_BLOCKER' ELSE 'PRESENT' END AS state
 FROM expected e LEFT JOIN information_schema.columns c
 ON c.table_schema='public' AND c.table_name=e.relation_name AND c.column_name=e.column_name
)
SELECT relation_name,column_name,state FROM evidence ORDER BY relation_name,column_name;
WITH expected(relation_name,column_name) AS (
 VALUES ('golden_data_provider','provider_id'),('golden_data_provider','provider_code'),
 ('golden_source_rights_receipt','provider_id'),('golden_dataset_backfill_job','provider_id'),
 ('golden_dataset_backfill_job','job_id'),('golden_dataset_backfill_chunk','job_id'),
 ('golden_dataset_backfill_chunk','status'),('golden_dataset_backfill_chunk','attempts'),
 ('golden_dataset_backfill_chunk','valid_records'),('golden_dataset_backfill_chunk','updated_at')
), missing AS (
 SELECT e.relation_name,e.column_name FROM expected e
 LEFT JOIN information_schema.columns c
 ON c.table_schema='public' AND c.table_name=e.relation_name AND c.column_name=e.column_name
 WHERE c.column_name IS NULL
)
SELECT CASE WHEN count(*)=0 THEN 'SCHEMA_COLUMNS_PRESENT_RIGHTS_UNVERIFIED'
 ELSE 'SCHEMA_BLOCKED' END AS preflight_status,count(*) AS missing_required_columns FROM missing;
-- Independently inspect Flyway migration history using approved metadata access.
-- Column presence is not migration proof. No rights receipt => no authorized coverage credit.
ROLLBACK;

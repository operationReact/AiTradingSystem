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
-- Privilege preflight is metadata-only. Even SELECT permission does NOT establish source-use rights.
WITH required(relation_name) AS (
 VALUES ('golden_data_provider'),('golden_source_rights_receipt'),
        ('golden_dataset_backfill_job'),('golden_dataset_backfill_chunk')
), permission_check AS (
 SELECT relation_name,
        to_regclass(format('%I.%I','public',relation_name)) AS relation_oid
 FROM required
)
SELECT relation_name,
       CASE WHEN relation_oid IS NULL THEN 'MISSING_RELATION'
            WHEN has_table_privilege(relation_oid,'SELECT') THEN 'READ_PERMISSION_PRESENT_RIGHTS_UNVERIFIED'
            ELSE 'READ_PERMISSION_BLOCKED' END AS read_preflight
FROM permission_check ORDER BY relation_name;

-- Single-row fail-closed gate for consumers; rights and actual coverage remain UNKNOWN.
WITH required(relation_name) AS (
 VALUES ('golden_data_provider'),('golden_source_rights_receipt'),
        ('golden_dataset_backfill_job'),('golden_dataset_backfill_chunk')
), inspected AS (
 SELECT relation_name, to_regclass(format('%I.%I','public',relation_name)) AS oid FROM required
), totals AS (
 SELECT count(*) FILTER (WHERE oid IS NULL) AS missing_relations,
        count(*) FILTER (WHERE oid IS NOT NULL AND NOT has_table_privilege(oid,'SELECT')) AS denied_relations
 FROM inspected
)
SELECT CASE WHEN missing_relations > 0 THEN 'SCHEMA_BLOCKED'
            WHEN denied_relations > 0 THEN 'READ_PERMISSION_BLOCKED'
            ELSE 'READ_METADATA_READY_RIGHTS_UNVERIFIED' END AS audit_gate,
       missing_relations, denied_relations,
       'UNKNOWN'::text AS authorized_verified_partitions,
       'UNKNOWN'::text AS historical_target_denominator
FROM totals;
-- Independently inspect Flyway migration history using approved metadata access.
-- Column presence is not migration proof. No rights receipt => no authorized coverage credit.
ROLLBACK;

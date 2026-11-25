-- Worker 3 / #1012 / #1074: persisted Upstox checkpoint inventory, not acceptance.
-- Execute only with approved read-only PostgreSQL credentials. No source calls.
-- Schema prerequisites: V77-V79 applied; inspect information_schema first.
-- All numbers below are CHECKPOINT observations, never authorized VERIFIED partitions.
-- A missing provider produces an explicit UNKNOWN state, not a false zero.
BEGIN TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY;
SELECT current_setting('transaction_read_only') AS read_only,
       transaction_timestamp() AS observed_at_utc,
       (SELECT count(*) FROM golden_data_provider WHERE provider_code='UPSTOX')
           AS upstox_provider_rows;
-- Stop and report SCHEMA_UNVERIFIED if expected relations/columns are absent.
WITH provider AS (
  SELECT provider_id FROM golden_data_provider WHERE provider_code='UPSTOX'
), observed AS (
  SELECT j.job_id, c.job_id AS chunk_job_id, c.status,
         c.attempts, c.valid_records, c.updated_at
    FROM provider p
    JOIN golden_dataset_backfill_job j ON j.provider_id=p.provider_id
    LEFT JOIN golden_dataset_backfill_chunk c ON c.job_id=j.job_id
)
SELECT CASE WHEN NOT EXISTS (SELECT 1 FROM provider) THEN 'PROVIDER_UNKNOWN'
            WHEN NOT EXISTS (SELECT 1 FROM observed) THEN 'REGISTERED_NO_JOBS'
            ELSE 'CHECKPOINTS_OBSERVED_RIGHTS_UNVERIFIED' END AS evidence_state,
       (SELECT count(*) FROM provider) AS provider_rows,
       count(DISTINCT job_id) AS jobs,
       count(chunk_job_id) AS chunks,
       count(chunk_job_id) FILTER (WHERE status='VERIFIED') AS status_verified_chunks_not_accepted,
       count(chunk_job_id) FILTER (WHERE status='FAILED') AS failed_chunks,
       count(chunk_job_id) FILTER (WHERE status='RETRY') AS retry_chunks,
       coalesce(sum(attempts),0) AS recorded_attempts,
       max(updated_at) AS last_checkpoint_at,
       NULL::bigint AS authorized_unique_verified_partitions,
       NULL::bigint AS planned_target_partitions,
       NULL::numeric AS coverage_percent
  FROM observed;
-- Rights must be evaluated independently by domain/use/validity before promotion.
-- Chunk status VERIFIED is insufficient: do not join or infer rights by provider alone.
-- Use a frozen, reviewed listing-episode/timeframe/month manifest for denominator.
ROLLBACK;

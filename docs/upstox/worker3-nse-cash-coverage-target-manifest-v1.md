# Worker 3 — Upstox NSE cash coverage target manifest v1 (proposal)

Status: **contract proposal only**; not a production count, runtime receipt, source authorization, or backfill job. Scope: read-only coverage reporting. Owner: Worker 3. Coordination: #924 #997 #1012 #1057 #1074 #1092. Base branch: dev.

## Immutable manifest identity

A reviewed manifest version MUST record:
- `manifest_id`, `schema_version=1`, `reviewed_at_utc`, `reviewer`, `as_of_exchange_date`, `created_at_utc`.
- `provider=UPSTOX`, `exchange=NSE`, `segment=NSE_EQ`, `source_domain`, `source_dataset_version`, `source_rights_receipt_id`, `rights_valid_from_utc`, `rights_valid_until_utc`, `permitted_use`, `retention_policy`.
- `universe_snapshot_id`, `universe_provenance`, `listing_episode_id`, `canonical_instrument_id`, `provider_instrument_key`, `symbol_at_as_of`, `listing_effective_from`, `listing_effective_to`, `isin_when_available`.
- `timeframe`, `window_start_utc`, `window_end_utc_exclusive`, `calendar_version`, `partition_granularity=MONTH`, `adjustment_policy`, `revision_policy`.

The target key is `(manifest_id, provider, exchange, listing_episode_id, timeframe, partition_start_utc)`; provider token or current symbol alone MUST NOT define identity. Each target's requested window is intersected with its listing episode and the manifest's fixed time window. Calendar and partial-month rules are versioned, never silently recalculated. A symbol rename does not create a second target for the same listing episode. A new listing episode does.

## Counts and denominator

`planned_unique_partitions` is defined ONLY after a reviewed, frozen as-of NSE cash listing universe, explicit timeframe set, bounded date window and documented partition policy exist. Deduplicate the target key BEFORE counting. Store a stable manifest hash, target count, and approval record. Missing universe, listing episode, window, rights domain, or provider registration yields `UNKNOWN` denominator; NEVER substitute zero or a guessed NSE stock count.

Per-target states are mutually exclusive:
- `VERIFIED`: persisted canonical partition receipt tied to the target key and source version, valid source rights for the claimed use and acquisition time, valid checksum and observed availability, and the expected window completeness rule satisfied.
- `CANDIDATE`: persisted attempt/receipt exists but verification or rights/completeness remains unresolved. No completion credit.
- `GAP`: observed partial data with a measurable missing interval and an authorized retry candidate; no completion credit.
- `MISSING`: target is fixed but no qualifying persisted receipt exists; no completion credit.
- `UNKNOWN`: target cannot be evaluated safely because provenance, rights, or database observation is unavailable; no completion credit.

Count each unique target once at the strongest **authorized** state; unauthorized or invalid receipts must not consume a target key. Retain all attempt evidence separately, including `last_attempt_at_utc`, `last_success_at_utc`, `source_status`, `http_429_count`, `retry_after_utc`, `error_reason`, `checkpoint_id`, `receipt_id`, `observed_at_utc`, and `source_revision`. Never infer VERIFIED from a PR, a sample candle, a job row alone, or a source login.

`verified_percent = 100 * unique_authorized_verified / planned_unique_partitions` only when the reviewed denominator is known and nonzero. Otherwise percentage is `null` with reason. Compare successive audits only against the **same manifest_id/hash** and rights policy; a changed universe is a new baseline, not an improvement.

## Read-only V77–V79 PostgreSQL audit handoff

1. Confirm actual deployed migration versions and inspect actual table/column definitions before writing production SQL. Do not assume migration file names imply deployed schema.
2. In one read-only, transactionally consistent snapshot, read provider registry, `golden_source_rights_receipt`, `golden_dataset_backfill_job`, and actual persisted partition/receipt relations, where present. Record audit timestamp, schema version and sanitized query checksum.
3. Resolve `UPSTOX EXTERNAL` registry identity; **registration is not rights approval**. Require time-valid rights receipt for each requested domain/use. If absent, report `rights=UNKNOWN`, `verified=UNKNOWN` (not a zero-percent whole-market claim).
4. Count distinct authorized target keys, not rows, API calls, candles, GitHub PRs or checkpoint attempts. Keep unknown and unauthorized rows visible as blockers, never verified.
5. Output a paginated read-only report ordered by `(listing_episode_id, timeframe, partition_start_utc)`, with stable cursor bound to `manifest_hash` and audit snapshot; include counts, reason codes and last attempt. Cursor mismatch fails closed.
6. Export retry candidates to Worker 1 as **recommendations only**, with bounded windows, rights gate, cooldown and source provenance; do not trigger source API calls or jobs.
7. Report before/after verified counts, newly unique receipt IDs and acceptance delta only after observing real persisted receipts. Otherwise report UNKNOWN and +0.0pp.

## Monday F&O isolation

This NSE_EQ historical target contract is **not** an NSE_FO executable quote source. #1074 remains blocked until independently verified active NIFTY NSE_FO contract/expiry/lot, rights, exchange-time freshness and session receipts exist. Market-closed snapshots are STALE. Worker 3 must not change source clients, SLP, PAPER execution, customer OAuth, Mind/Rooms, or Flutter UI.

## Current evidence limitations (2026-11-15)

Prior owner-provided audit: UPSTOX EXTERNAL registry identity exists; zero rights receipts and zero backfill jobs were reported at that time. **No fresh production V77–V79 database observation is included in this proposal.** Planned denominator UNKNOWN; authorized verified partitions UNKNOWN; acceptance change +0.0pp. This document must not be used as evidence of ingestion.

## Executable schema-discovery preflight (read-only; no market-data calls)

Run with a **read-only PostgreSQL role** against the explicitly approved environment. These queries inspect metadata only and make no assumptions about whether migrations V77–V79 were applied. Record a sanitized result digest, not raw source rows or credentials. The transaction must be rolled back even on success.

```sql
BEGIN TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY;
SELECT current_database() AS database_name,
       current_setting('transaction_read_only') AS transaction_read_only,
       current_setting('server_version_num') AS server_version_num;
SELECT installed_rank, version, success, installed_on
FROM flyway_schema_history
WHERE version IN ('77', '78', '79')
ORDER BY installed_rank;
SELECT table_schema, table_name, column_name, data_type, is_nullable
FROM information_schema.columns
WHERE table_schema NOT IN ('pg_catalog', 'information_schema')
  AND table_name IN ('golden_source_rights_receipt', 'golden_dataset_backfill_job')
ORDER BY table_schema, table_name, ordinal_position;
ROLLBACK;
```

If `flyway_schema_history` is absent, permissions deny metadata, or the actual migration version names differ, stop and report `SCHEMA_UNVERIFIED` rather than treating empty output as zero. Do not construct receipt joins or a denominator until the actual deployed columns and keys have been independently reviewed. A successful metadata query is **not** a verified partition or a rights grant.

### Required regression cases for the eventual persisted query

- Two receipts for one partition count as one; an unauthorized earlier receipt must not shadow an authorized later one.
- Rights valid at acquisition but expired for the requested current use must fail closed; source login alone never qualifies.
- Renamed symbol within one listing episode must not double the denominator; distinct listing episodes must remain distinct.
- A missing universe snapshot or rights scope returns null denominator/percentage, never a fabricated zero.
- A cursor from another manifest hash or observation snapshot must be rejected; stable ordering must survive pagination.
- A 429 attempt/cooldown or incomplete month is reported as retry candidate or gap, not VERIFIED.

## Additional audit safety: fixed denominator and before/after comparison

The audit operator must record the same `manifest_hash`, `source_rights_receipt_id`, `snapshot_as_of_utc`, `schema_digest`, and `observation_id` for every result page. If any is absent, return `AUDIT_INCOMPLETE` and **null** totals. A snapshot must be stable across pages; a newly opened transaction cannot reuse a cursor from an earlier snapshot unless a materialized immutable audit result is available. This prevents page drift from manufacturing a progress increase.

Never use `COUNT(*)` over backfill jobs, attempts, candle rows, or receipts as the denominator. The reviewed target manifest is the only denominator source. An expired, mismatched-domain, or unapproved rights receipt does not authorize counting historical partitions as VERIFIED. Revisions are selected by a documented deterministic version policy and retain rejected receipt IDs for audit, without duplicating a target.

For every comparison, emit both observation identifiers and counts only if the *same* manifest hash and rights policy are present. Otherwise report `NOT_COMPARABLE`, null percentage and +0.0pp acceptance. If the current persisted data is unavailable, report `NOT_OBSERVED`, not a synthetic zero. Any proposed retry queue remains a read-only export, not an ingestion trigger.

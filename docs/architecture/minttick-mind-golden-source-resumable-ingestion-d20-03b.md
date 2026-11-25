# Mintick Mind D20-03B — resumable historical ingestion control plane

**State:** implemented as dormant PostgreSQL/Java custody and an internal dependency-injected coordinator. No provider license is purchased, no source adapter or S3 archiver is installed, no API/scheduler is exposed, and **zero 20-year rows are collected by this change**.

## Boundary and workflow

- Starts from **V77 permanent historical listing IDs**, not currently mapped Angel One tokens. Rejects dates before listing or on/after delisting; no automatic mapping to today's symbol.
- Plans one idempotent (provider, listing, source domain, interval, range, revision) job into maximum **31-calendar-day** chunks, bounded by an absolute **25-year** maximum. This is planning capacity, not verified historical provider coverage. A revised or corrected source dataset must use a new revision.
- **V79** adds durable job, chunk, lease, retry, immutable output metadata and provider cooldown tables. Each worker atomically leases one due chunk using PostgreSQL `FOR UPDATE SKIP LOCKED`. Five attempts maximum, five-minute leases, exponential backoff capped at 30 minutes plus bounded deterministic jitter, persisted provider-wide cooldown. Lost workers' leases can be reclaimed; stale lease tokens cannot checkpoint. Invalid and incomplete chunks never advance to verified.
- `GoldenHistoricalDatasetProvider` has no broker order/session interface and may return only a bounded byte payload (max **32 MiB**) for one request. No production provider adapter is shipped.
- `GoldenDatasetChunkValidator` must inspect the actual payload, listing episode, session calendar, required domain fields, duplicate/invalid records, as-of availability and revision evidence, and supply independent expected/valid observations. A vendor's count/HTTP 200 cannot be substituted for this validator. **No production validator is shipped**.
- `GoldenChunkArchive` must write immutable, encrypted, versioned data and verify the checksum on durable readback. The coordinator computes the SHA-256 of the source bytes and refuses mismatched archival receipts. **No production S3 sink or bucket is shipped**.
- A checkpoint is fenced by the current unexpired lease token and recorded only after independent validation, archive receipt checksum match, and an additional rights recheck. `VERIFIED` requires nonzero expected records and exact validation coverage; positive but partial windows are recorded in the existing V78 source-specific coverage table as `INCOMPLETE` and block completion. Zero observations remain incomplete in chunk metadata and do not invent V78 coverage dates.

## Rights and safety

At execution time (not simulated trade date), the private V78 registry must authorize **RAW_RETENTION** for the exact external provider/source. No receipt, expired or revoked receipt, or source mismatch: **no source call**. A separate **MODEL_TRAINING**, **MODEL_INFERENCE**, **END_USER_DISPLAY** or **REDISTRIBUTION** grant remains mandatory before each corresponding downstream use. The fact that fixtures pass tests does not prove actual licensing, exchange validity or S3 durability. Existing live feed, broker sessions, account ownership, execution/risk policy and Flutter UI remain unchanged.

Concurrency fences prevent stale workers from completing a reused lease, but do not guarantee a remote request is never repeated after a crash: archives must be **content-addressed and idempotent**. An entitlement revoked during or after an archival write needs a separately reviewed audit/deletion workflow; this coordinator blocks subsequent usage but cannot retroactively erase persisted bytes. DB coverage receipts are evidence references, not external legal verification.

## Operator rollout dependencies (D20-04 onward)

1. Independently verify commercial license and coverage for each provider/source, including NSE/BSE daily history, delisted listings, and each permitted use. Only an audited private workflow may populate the rights registry.
2. Supply a tested source-specific historical provider, session/calendar-aware validator, secure immutable S3 archiver and source-specific throughput/rate policy. No such production adapters are assumed here.
3. Add strictly isolated, disabled-by-default worker orchestration, revision reconciliation, incident controls and source rights revocation/purge procedures. Do not run bulk ingestion on the small EC2 PAPER runtime.
4. Execute one *licensed* listing/date-window first; reconcile expected exchange sessions, corrections and real archive hashes; then scale gradually 1 → 50 → 500 → historical universe only with coverage and cost evidence.
5. Before model data use, bind the immutable source manifests to D20-01B's 18-model feature requirements and all 43 Flutter Mind routes, 232 prototype strategy templates, and the point-in-time availability gate. Missing mandatory sources cause **BLOCKED/ABSTAIN**, never synthetic values or zero-filled substitutions.

## Tests and observability

`GoldenSourceBackfillCoordinatorIT` uses only synthetic Postgres test fixtures, a mock validator and a fake archive. Tests check empty-rights no-call, idempotent planning, bounded windows, complete and incomplete coverage, transient provider-wide retry, worker-crash lease reclaim, digest mismatch quarantine, and prior-grant revocation. `progress(jobId)` reports per-job source/scope state, chunk counts and expected/valid totals; it is internal read-only instrumentation, **not a model-ready assertion**.

This slice is intentionally additive and does **not** change `market_candle`, V8 historical candles, real Angel One owner-feed flags, order execution or deployment.

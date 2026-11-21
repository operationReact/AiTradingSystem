# MintTick — expired NSE_FO historical backfill preparation

**Current status: preparation / offline unit tests only. No scheduled downloads, no API fetches, no historical market-data storage.**

The bounded read-only Upstox source check on 2026-11-21 confirmed one expired NIFTY futures contract and five valid daily OHLCV/OI candles ([receipt](https://github.com/operationReact/AiTradingSystem/issues/1504#issuecomment-7059398317)). This establishes sample access, **not** bulk licensing, coverage, or persistence.

## What is implemented

The standalone Python module at `infra/ec2-paper/fno_history_backfill.py` is a dormant planner and worker core, **not** wired into GitHub Actions, EC2 cron, Spring Boot, Flutter, AWS, the live broker feed, or any market-data database.

- Explicit time-limited retention/use approval required before both planning and fetching. Plus availability alone does not establish rights to archive or reuse market data.
- Provider-sourced expired NSE_FO instrument keys with inclusive deterministic windows: day 30 calendar days; 30/15-minute 7 days; 5/3-minute 3 days; 1-minute 1 day. Conservative engineering defaults, **not** provider-guaranteed range limits.
- Durable SQLite **metadata-only** journal with UNIQUE per contract, interval and window, atomic claims, 90-second lease, interruption recovery and retry-safe idempotency key.
- Explicit 401/403 BLOCKED, retry 429/5xx with bounded backoff and Retry-After, transport/sink retry, invalid-payload FAILED, valid HTTP 200 without bars EMPTY, committed bars DONE. At most five provider attempts per job.
- Validate seven-element candles including timezone, date range, OHLC, nonnegative integer volume, open interest and duplicate timestamps.
- Metadata progress response: planned/remaining/done/empty/blocked/failed/retry batches, rows committed, percent complete, and last completed UTC timestamp. Progress counts are genuine **planned batches**, not hypothetical exchange-wide coverage.
- Serial pacing default one GET per 1.5 seconds. Multi-process workers must add a shared limiter.

Official [Upstox rate limits](https://upstox.com/developer/api-documentation/rate-limiting/) describe other Standard APIs, including historical candles, as 50 requests/second, 500/minute and 2000/30 minutes (per API per user). Those are ceilings, not targets. Coordinate with other Upstox callers under the same user account.

## Remaining integration / acceptance gates

1. Confirm written **bulk history retention, dataset date range, storage, backtesting and reuse rights** for NSE_FO via the provider. Do not infer rights from the subscription tier.
2. Authorized backend adapter must supply a `RetentionGrant`, actual `ExpiredProvider` using the fixed explicit User-Agent and an `IdempotentSink` with transactional deduplication. No credentials belong in metadata or Flutter.
3. Test one contract/timeframe at a time and reconcile source candle count vs sink commit. Restart mid-batch and prove no duplicate market-data rows.
4. Expose the authenticated read-only metadata progress snapshot from Spring Boot and show real completed/remaining/blocked/retry/last-update values in the Flutter operations screen. **Not implemented in this PR.**
5. Verify two consecutive checkpoint advances, 429 handling, cost boundaries, operator pause/resume and provider rights before enabling any schedule or historic bulk storage.

## Sample initial progress contract (not a live dataset)

- source: upstox-expired-nse-fo
- state: PREPARATION_ONLY
- retention_rights: EXTERNAL_APPROVAL_REQUIRED
- total_batches: 0
- completed_batches: 0
- completion_pct: 0.0
- rows_written: 0
- last_completed_utc_epoch: null
- counts: PENDING 0, ACTIVE 0, RETRY 0, DONE 0, EMPTY 0, FAILED 0, BLOCKED 0

There is no automatic rollout, token rotation, order execution, LIVE activation or downloader restart. The verified three-GET source probe remains unchanged.


## Second integration stage — implemented, but NOT activated

`upstox_fno_backfill_adapters.py` supplies:
- An actual Upstox expired-instrument candle HTTP GET adapter for the documented
  `/v2/expired-instruments/historical-candle/{expired_instrument_key}/{interval}/{to_date}/{from_date}`
  endpoint. It uses a descriptive User-Agent, URL allowlist, no redirects, bounded
  response parsing, classified HTTP status, and bounded Retry-After.
- A durable SQLite candle sink keyed by `source + expired contract + interval +
  candle timestamp`, with atomic batch receipts and conflict-safe upsert. A
  checkpoint is marked complete only after its sink confirms transaction commit.
- A SECOND independent, default-DENY `verifier(grant)` requirement at both
  provider and sink boundaries. A syntactically valid grant reference by itself
  is not enough to perform a request or open a storage file. An authorized
  integration must verify real source terms and grant provenance externally.
- Offline integration tests: provider GET with fake transport, 429 retry delay,
  malformed provider replies, deny-without-verification, SQLite replay dedupe,
  revocation, and end-to-end fixture checkpoint and progress.

**No valid retention grant or external verifier is configured or shipped in
this repository.** No downloader scheduler or live AWS authorization is created,
and no market-data storage has been performed through this integration. The
database fixture in tests is temporary and uses synthetic test-only candles.

The Spring Boot authenticated REST progress endpoint and Flutter screen are
**still pending**. Progress currently exists as Python `MetadataQueue.summary()`
and `status_json()`, not a connected app view.

### Precise remaining activation conditions

1. Confirm provider terms for persistent expired NSE_FO history / backtesting and
   document the scope of allowed contracts, timeframe, term, and retention.
2. Supply a trusted external attestation verifier and a scoped credential
   provider, avoiding secret logging; deny when authorization expires.
3. Run a narrow authorized provider-to-sink test, verify rows and OI against
   the source, restart and prove two separate checkpoint advances.
4. Expose only sanitized metadata via authenticated backend REST and Flutter;
   set up a single operator-controlled scheduler and shared provider rate limiter.


## Customer-authenticated checkpoint progress bridge

This phase adds an OPTIONAL metadata bridge. It is **not yet a live collector**.

- `fno_history_progress_snapshot.py` converts the local SQLite checkpoint
  journal into a **bounded, atomic, owner-private 0600 JSON snapshot** with
  batch states only. It never includes contract identifiers, candle prices, OI,
  broker credentials or bearer tokens. No exporter is scheduled or invoked by
  existing production workflows.
- Spring Boot `GET /api/customer/fno-history/progress` is covered by the
  existing CUSTOMER role requirement. The backend reads only the explicitly
  configured `FNO_HISTORY_PROGRESS_SNAPSHOT_PATH`; the default empty setting
  returns `NOT_CONFIGURED` with unknown counts and **no percentage**.
- The endpoint validates the snapshot schema, source, journal totals,
  completed/empty counts, timestamps and percentage; a snapshot older than
  ten minutes is `STALE`. Any malformed or unsafe snapshot fails closed as
  `UNAVAILABLE`. Paths and data payloads are never sent to the client.
- Flutter adds a compact Upstox F&O progress section atop the existing
  customer Historical Data Monitor. This section does not imply live running,
  data-retention permission or an active collector. It can show a planned
  batch percentage only when it receives a valid non-empty journal snapshot.

**Current deployment state:** no snapshot publisher has been connected to a
runtime scheduler, no env path has been configured, and no historical data
ingest has been activated. The UI therefore reports "not connected" rather
than a synthetic 5% or 100%. An authorized runner must supply a legitimate
checkpoint database and explicitly call the exporter; the backend must be
configured with the snapshot's controlled path. This wiring requires the
provider rights and operational review described above.

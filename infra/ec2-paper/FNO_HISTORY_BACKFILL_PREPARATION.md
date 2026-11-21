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

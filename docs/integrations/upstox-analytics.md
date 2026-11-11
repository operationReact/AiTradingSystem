# MintTick — Upstox Analytics Token (read-only source, Phase 1)

## Scope and security
This integration is **not** the existing Upstox OAuth customer-broker connection.
It consumes the user's **one-year read-only Analytics Token** from AWS SSM
`/minttick/upstox/analytics-token` (SecureString in `ap-south-1`).
It does not accept the token in an API request, log it, return it, place orders,
enable LIVE, stop Angel One, or launch an automatic downloader.

Authoritative Upstox documentation:
- https://upstox.com/developer/api-documentation/analytics-token/
- https://upstox.com/developer/api-documentation/v3/get-historical-candle-data/
- https://upstox.com/developer/api-documentation/get-key-ratios/
- https://upstox.com/developer/api-documentation/rate-limiting/

## What this first slice does
- Implements a bounded `UpstoxAnalyticsSource.dailyEquityCandles` (31 calendar days
  maximum per call) using Upstox **V3** historical daily candle API. Validates
  timestamps, OHLC, volume, OI (the seventh Upstox field), duplicate timestamps,
  and per-request dates; it does not reuse Angel One's six-field parser.
- Implements `UpstoxAnalyticsSource.currentRatios` by ISIN using the official
  fundamentals key-ratios API. **These are current ratios, not point-in-time
  historical fundamental features.**
- Adds a private `ADMIN`-only, POST-only, non-persisting readiness probe at
  `/api/admin/market-data/upstox-analytics/probe`. The probe samples a bounded
  Reliance NSE cash-equity history window and ratios, with a 15-minute cooldown.
- No S3/raw-retention, PostgreSQL writes, background scheduling, feature/model
  publishing, broker-session changes, or API Gateway public route are introduced.
  Both switches default OFF.

## Exact activation prerequisites
1. The parameter exists as `SecureString` in AWS SSM in `ap-south-1`.
   The human operator creates/rotates its value in AWS, **never GitHub/chat**.
2. Review the **existing EC2 instance-profile policy**. The backend needs
   `ssm:GetParameter` for the exact parameter ARN:
   `arn:aws:ssm:ap-south-1:098859530205:parameter/minttick/upstox/analytics-token`.
   Do not grant wildcard access. If encrypted with a customer-managed KMS key,
   add scoped `kms:Decrypt` only after verifying key policy and account.
   Existing Angel One parameter permissions do not prove Upstox access.
3. After backend deployment using the existing controlled dev release, set in the
   protected host `runtime.env` (not the client or committed repo):
   `MINTTICK_UPSTOX_ANALYTICS_ENABLED=true`
   `MINTTICK_UPSTOX_ANALYTICS_PROBE_ENABLED=true`.
   Preserve the existing Angel One and PAPER settings.
4. With a verified local ADMIN session, POST the probe endpoint against
   `127.0.0.1:8080`. Do not route it through the public customer gateway.
   The response contains only counts and safe statuses: `READS_VERIFIED`,
   `PARTIAL_DATA`, `PROVIDER_UNAVAILABLE`, `DISABLED`, or `COOLDOWN`.
   No tokens, upstream error payloads, full responses or broker balances are emitted.
5. Capture runtime evidence (revision, date range, API status, validated counts,
   IAM permission, provider limits, dataset rights) without any secret values.

## Separation of source access from data rights
An authenticated HTTP 200 **does not establish** historical retention,
model-training, model-inference, public display, or redistribution permission.
Follow the existing `golden_source_rights_receipt` contract: separate explicit
rights for every intended use, source-domain evidence and expiry.

Do not start full-universe backfills until source rights, stable historical listing
identity (including inactive/delisted securities), quotas, and persistent validated
dataset lineage are independently approved. The Golden Source ingestion coordinator
and Angel One historical collector are deliberately unchanged by this PR.

Upstox standard API published caps: 50/s, 500/minute, 2,000/30min per user.
This limited probe is designed for operator smoke tests, not to consume a
full-universe quota. A future ingest adapter needs its own distributed rate limiter,
checkpointing, exponential backoff, source-rights checks and expiry monitoring.

## Next integration slices
1. Use tested current instrument-key mapping to stable `listing_id` across
   symbol changes; add the Upstox source adapter to `GoldenHistoricalDatasetProvider`
   only after rights review.
2. Add statement/shareholding/corporate-action feeds with publication timestamps
   and raw-vs-adjusted lineage.
3. Per-instrument/year coverage reports and safe fallback to Angel One only for
   genuinely verified missing windows (never overwrite conflicts silently).
4. Publish datasets to Mintick Mind only after quality, provenance, licensing,
   point-in-time and backtesting gates pass.

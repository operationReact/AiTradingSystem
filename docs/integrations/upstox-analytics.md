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

## Phase 2A — bounded multi-timeframe history adapter (no downloader)

The read-only `UpstoxAnalyticsSource.historicalEquityCandles` API supports
validated V3 equity candles with the following deliberately conservative
**MintTick per-call limits** (the vendor may allow more):

| Unit | Intervals | Maximum date span per call |
| --- | --- | --- |
| minutes | 1–15 | 2 calendar dates (difference <= 1 day) |
| minutes | 16–300 | 31 calendar dates (difference <= 30 days) |
| hours | 1–5 | 31 calendar dates |
| days | 1 | 366 calendar dates |
| weeks | 1 | 3651 calendar dates |
| months | 1 | 3651 calendar dates |

- The existing private acceptance probe still calls `dailyEquityCandles`, a
  maximum 31-calendar-date compatibility wrapper.
- No requests are issued for unsupported intervals, invalid equity instrument
  keys, future dates, or out-of-bounds windows. Successful responses must pass
  timestamp, duplicate, OHLC, volume and open-interest validation.
- Maximum 1024 candles and 256 KiB of upstream response text are accepted per
  request. Large responses fail closed rather than silently truncating data.
- This phase **does not** run a full-universe scheduler, persist or publish
  vendor data, grant historical training rights, alter user OAuth connections,
  or enable paper or real orders.

**Why:** Upstox V3 documents minute/hour history since January 2022 and
daily/weekly/monthly history since January 2000, subject to each instrument's
actual history and the provider's documented interval limits:
https://upstox.com/developer/api-documentation/v3/get-historical-candle-data/

Next: reconcile Upstox instrument keys to episode-aware MintTick listings,
verify actual provider reads using the private probe, document applicable source
retention/training rights, and implement checkpointed rate-limited ingestion
behind the existing Golden Source rights gate. Preserve Angel One as a separate
historical source. Never silently substitute one broker's candles for another's
without lineage and quality evidence.

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
4. With a verified local ADMIN or OPERATOR session, POST the probe endpoint against
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


## One-shot activation through the restricted GitHub SSM role
After the first backend release passes connected EC2 health and the owner has
stored a SecureString in the **same exact SSM parameter**, a separate
single-file, dev-only activation request can be created:
`infra/ec2-paper/requests/upstox-analytics-activate.json`.

The workflow `.github/workflows/ec2-paper-upstox-analytics-activate.yml`
enforces the exact request payload, the existing AWS account/region/EC2 identity,
the enabled 22:00 IST cost-stop schedule, SSM Online, and serializes alongside
the normal EC2 deploy workflow. A fixed, reviewed script on the host:
- Reads **only the type** of the designated SSM parameter, decrypted under
  the existing EC2 instance profile. Nothing is logged from the token.
- Fails without changing runtime settings when EC2 lacks permission to read it.
- Keeps the original root-owned host runtime.env as a private rollback copy,
  enabling **only** the two Upstox analytics flags, then invokes the existing
  PAPER-only local deployment health gate.
- Calls the local POST-only operator/admin probe using the host's existing
  operator key, without logging or exporting that key.
- Accepts only genuine nonzero validated daily candles and ratio counts with
  both `stored=false` and `liveTradingEnabled=false`.
- Restores the old runtime.env and service if the private source probe fails.

This is a **one-shot verified source read**, not background ingestion or
permission to retain datasets/model-train. No public API Gateway route or new
IAM permissions are created by this workflow. If the read fails with
`SSM_UNAVAILABLE`, the EC2 role's `ssm:GetParameter` for the exact ARN
must be reviewed by the account owner. Never upload the credential to GitHub.


## Manual one-shot verification (no token disclosure)
A reviewed operator may open
[the private Upstox activation workflow](https://github.com/operationReact/AiTradingSystem/actions/workflows/ec2-paper-upstox-analytics-activate.yml),
choose **Run workflow**, select `dev`, and enter the exact confirmation
`AUTHORIZE_PRIVATE_UPSTOX_READONLY_ACCEPTANCE`.

This is an explicit human-triggered alternative to the historical single-file
request mechanism. It invokes the **same** exact account/host, PAPER mode, 22:00
IST stop-schedule check, existing GitHub OIDC role, SecureString-only SSM
read, checked script transport, loopback-only probe, and rollback controls.
The workflow refuses wrong branches, confirmations and unknown event types.
No token value is supplied to the workflow.

A passing run must show a fixed `UPSTOX PRIVATE ACCEPTANCE PASSED` line
with positive **validated daily candle** and **current ratio** counts.
An `ACTIVATED_READ_ONLY` marker alone is insufficient without those counts.
A failed run means collection is **not verified**, even if CI passed.
Do not claim permission to retain, train or distribute upstream datasets
from an authentication smoke test.

# Upstox Analytics progress — Spring Boot + GitHub + MintTick Mind

## What is implemented
- Flutter **Settings → Upstox Analytics** opens a native, authenticated, owner-only
  read-only progress dashboard matching the single MintTick design system:
  near-black `#090B0A`, flat panel `#151916`, matte emerald `#3D9567`.
- Seven interactive tabs: Overview, Tasks, History, Quality, Mind, GitHub and
  Paper. Timeframe chips, job detail sheets, refresh, and copyable PR URLs.
  **No fake historical coverage or backfill percentages.**
- Owner-only `GET /api/customer/owner-upstox-analytics/v1/progress`
  (Cognito JWT subject must match the existing owner-subject configuration).
  No credentials, holdings, account funds, raw candles or trading authority.
- PostgreSQL Flyway V85 stores operational task state, bounded aggregate counts,
  safe details, attempt/success timestamps and a 180-second crash-expiring lease.
  The eight task rows default to NOT_STARTED or DISABLED until verified.
- Independent Spring Boot `@Scheduled` jobs:
  - GitHub read-only PR/recent CI poll (five-minute default) from the fixed
    `operationReact/AiTradingSystem` repository, no webhook/deploy/write access.
  - Opt-in private Upstox Analytics smoke-read (30-minute default) of a bounded
    seven-day RELIANCE daily-history window and current key ratios, persisting
    **only counts and safe status**. Reuses the SSM-backed Analytics token source.
- GitHub downtime or missing read credentials **cannot stop** independent Upstox
  data jobs, even after future ingestion schedulers are added.

## Runtime configuration
- `MINTTICK_GITHUB_PROGRESS_ENABLED=true` (default): permits read-only GitHub
  monitor polling. Private repositories require a separate, least-privilege,
  *backend-only* `MINTTICK_GITHUB_READ_TOKEN` (GitHub App read access preferred).
  Provision securely via AWS SSM/host runtime; never commit or place in Flutter,
  mobile packages, query strings, logs or Github Actions outputs.
  On inaccessible GitHub, UI shows BLOCKED/STALE, never fake zero PR counts.
- `MINTTICK_UPSTOX_PROGRESS_SOURCE_PROBE_ENABLED=true` (default). This schedules
  only a bounded, non-persisting smoke read (two API requests at most every 30
  minutes) **after** `MINTTICK_UPSTOX_ANALYTICS_ENABLED=true` has been explicitly
  enabled on the protected host. Explicit `...SOURCE_PROBE_ENABLED=false` stops
  monitoring. Source authorization, SSM access and rate limits must still pass.
  The probe is **not** a full-universe downloader and grants no data rights.
- Spring scheduler fixed-delay properties (milliseconds):
  `minttick.upstox-analytics.progress.github-poll-ms` (default 300000),
  `minttick.upstox-analytics.progress.source-poll-ms` (default 1800000).
  Neither is an LLM/ChatGPT scheduled worker.
- No new AWS services or environments are created. No GitHub control token is
  requested from the user in chat.

## Progress contract
- Each task has `code`, `state`, nullable `checked/verified/issues`, `stale`,
  `reasonCode`, bounded `details`, `lastAttemptAt`, `lastSuccessAt`.
- `VERIFIED` for GitHub means the sample of PR/CI metadata was read,
  **not** that every PR merged or build passed. Detailed CI results are
  separately provided.
- The History tab displays unavailable coverage until independent per-instrument,
  interval and year coverage evidence is produced.
- Source status `VERIFIED` means limited reads succeeded with nonzero sample
  candles/ratios, **not** that retention, model training or redistribution rights
  were granted.
- `MIND_DATASETS`, `HISTORICAL_INGESTION`, `QUALITY_RIGHTS` and
  `PAPER_READINESS` are intentionally NOT_STARTED. Future scheduled work must
  update them only with genuine provenance/evidence; do not mark completion on
  a GitHub merge or plain successful HTTP response.

## Next independent code increments
1. Implement the rights-gated canonical Upstox backfill job executor and per-year/
   interval checkpoint/coverage counters (no silent fallback to Angel One).
2. Integrate real canonical source-aware datasets into the **existing** MintTick
   Mind feature and strategy services; document source/provenance and as-of time.
3. Build risk-gated PAPER strategy evaluation with authoritative fresh exchange
   quote and virtual ledger; do not enable live broker orders.
4. Expand backend task metadata to show verified histories, failure/retry reasons
   and actual training/model state. Keep personal holdings/funds isolated.

Related: #924 (unified Mind source contract), #918 (Upstox roadmap),
#917 (multi-timeframe V3 adapter), #922 (research scanner bridge).

## GitHub BLOCKED diagnostics (13 November 2026)
An owner screenshot showed `GITHUB_API_UNAVAILABLE` for GitHub development
and `DISABLED` for Analytics source verification. The public GitHub API
successfully served `/repos/operationReact/AiTradingSystem/pulls` and
`/actions/runs` when checked externally; the app/backend status is genuine,
but the old all-purpose error code did not distinguish container DNS/egress,
TLS, HTTP authorization, API rate limiting or invalid response data.

The GitHub reader now requests fixed **absolute** URLs, categorizes these
failures into bounded status codes, and avoids storing HTTP response bodies or
credentials. The owner Flutter task details translate diagnostic codes into
plain-language explanations. This change makes failures **diagnosable**, but
must not be reported as proof that EC2 container egress has already recovered.

The backend Compose now forwards
`MINTTICK_UPSTOX_PROGRESS_SOURCE_PROBE_ENABLED` from secure host runtime env,
default `false`. To begin bounded source verification, operator must approve
and configure **both** `MINTTICK_UPSTOX_ANALYTICS_ENABLED=true` and
`MINTTICK_UPSTOX_PROGRESS_SOURCE_PROBE_ENABLED=true` after confirming the
read-only SSM token rights and API quotas. The probe stores just safe counts;
it does not grant retention, training, full backfill or trading authority.

After deployment, check owner-only progress endpoint, inspect the new
`GITHUB_*` reason code, address a confirmed network/credential/rate-limit
blocker, then verify `GITHUB_DEVELOPMENT=VERIFIED` with nonempty recent PR
and CI snapshots. Never mark unrelated Analytics tasks complete merely because
GitHub polling succeeded.

## Activation and UI correction (13 November 2026)
- The private acceptance script now persists the **third** required switch
  `MINTTICK_UPSTOX_PROGRESS_SOURCE_PROBE_ENABLED=true` alongside the source
  and private acceptance-probe flags. Previously it omitted this setting and
  the Flutter page could remain `SOURCE OFF` after an accepted source read.
- The Docker Compose default now enables only **progress probing**; the
  independent `MINTTICK_UPSTOX_ANALYTICS_ENABLED=false` source gate stays off
  until operator activation. No historical ingestion, retention or model
  training is activated by this change.
- SOURCE_VERIFICATION now distinguishes `ANALYTICS_SOURCE_DISABLED` from
  `SOURCE_PROBE_DISABLED`. Disabled rows update their reason even when already
  disabled, avoiding stale `OPERATOR_OPT_IN_REQUIRED` messages.
- Flutter Overview is a compact, tappable single pipeline instead of a
  duplicate of Tasks. The status tag is `PROBE OFF`, not a claim that a
  separate Upstox trading-account OAuth session is disconnected.
- Deploying the code does **not** establish that the host is online, the
  analytics token works, or that dataset rights exist. Verify with the
  owner-only progress endpoint and acceptance workflow after deployment.

## Activation failure diagnostic follow-up (13 November 2026)
- One-shot private acceptance run 43335068276 failed after SSM preflight with
  `LOCAL_RELEASE_UNCLASSIFIED`. The preceding PAPER deployment run
  43335030317 succeeded; Upstox source verification is **not** complete.
- Each reviewed PAPER host deploy now emits a fixed `MINTTICK_PAPER_FAILED_STAGE`
  code on errors. The activation script maps those codes to bounded, safe
  `LOCAL_RELEASE_*` reasons. No raw host log, token or SSM stdout is displayed.
- The activation workflow prioritizes `ROLLBACK_UNVERIFIED` if restoration
  fails, so operators do not mistake a rollback problem for a source API failure.
- These changes improve diagnosis; they do **not** prove or repair the unknown
  infrastructure/application issue, nor do they license data retention.
- Do not retry activation until an exact-host PAPER deployment containing
  the new stage markers has passed and the operator can verify the current
  server state. Automatic PAPER/LIVE orders remain outside this workflow.

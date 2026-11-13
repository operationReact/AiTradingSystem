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

## Explicit source activation failure codes (13 November 2026)

After verified EC2 and SSM prechecks, activation attempts 43386821320 and
43387320262 failed with the generic `LOCAL_RELEASE_UNCLASSIFIED` error.
The actual upstream/host cause was not visible. Early `runtime.env` checks
could previously exit under a generic `activation` stage before invoking
the PAPER release script.

The guarded read-only activator now reports a **single** sanitized
failure reason. Failures in staging, validating the two existing host secret
formats, installing protected runtime configuration and re-running the existing
PAPER health checks are distinguished. A failed rollback always reports
`ROLLBACK_UNVERIFIED` over the original error. No secret values, Compose
configuration, provider payloads, or actual authorization tokens are printed.

This correction does not itself authorize Upstox data retention or establish
that source verification succeeds. Use the next owner-approved activation run
to determine which exact stage still fails. Do not assume a passing GitHub CI
build proves the live provider is functioning.

## SSM success without source acceptance proof (13 November 2026)
The user-triggered dev acceptance run [43391186744](https://github.com/operationReact/AiTradingSystem/actions/runs/43391186744)
failed after a successful SSM precheck, reporting `Upstox host script missing acceptance marker`.
This means a successful SSM command status did **not** carry the required
`ACTIVATED_READ_ONLY` terminal proof. It does not prove that Upstox data was
read successfully, and the cause of the missing marker remains unresolved.

The guarded host script now fails closed if it exits normally without setting
its verified-acceptance flag. The GitHub verifier reports a bounded, known
`MINTTICK_UPSTOX_RESULT` reason if present even on SSM Success; if absent,
it emits `HOST_ACCEPTANCE_PROOF_MISSING`, never raw SSM stdout or secrets.
The fixed `ROLLBACK_UNVERIFIED` marker takes priority. Nothing bypasses the
positive candle/ratio-count checks or activates LIVE orders.

## Backend-only activation correction — 13 November 2026

The approved source acceptance run 43396030458 failed with
`LOCAL_RELEASE_RESTART_UNCLASSIFIED`. The prior activation script called the
full PAPER deployment for an Analytics flag change, unnecessarily repeating
a database dump, full-stack Compose orchestration and web/bridge checks.
That failure code alone does not prove which of those release steps failed.

The replacement activator checks the exact installed release SHA and ARM64
non-root image, registered egress IP, live API health, running PostgreSQL,
root-only runtime files and SSM SecureString access. It validates the
resulting configuration with strict `TRADING_MODE=PAPER` and loopback-only
backend binding before changing anything. It updates only three read-only
Upstox Analytics switches in both protected environment files and recreates
only the existing backend using `--no-deps --no-build --force-recreate`.

If backend recreation or the bounded Upstox smoke read fails, it restores
both protected environment files and re-creates the previous backend.
Unverified rollback is always a hard failure. No credentials, expanded
Compose configuration, provider payloads, or raw host logs are emitted.

The protected GitHub workflow keeps its original fixed-error whitelist.
A new host-only error code may appear there as `HOST_UNAVAILABLE`, but it
cannot be interpreted as successful activation. Provider success still
requires positive validated daily-candle and fundamentals ratios counts.
No data retention, model training rights, or broker orders are enabled.

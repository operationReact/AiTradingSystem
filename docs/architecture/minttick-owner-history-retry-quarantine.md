# MintTick owner-private history D20-06D — fail-safe next stock batching

Status: code-only, private personal research on existing Angel One owner account. **No new market-data API calls, no new paid infrastructure, no model training or live trading.**

## Actual verified retained data

[Owner-private successful historical pilot](https://github.com/operationReact/AiTradingSystem/actions/runs/42851887972/job/129954571949): **24,772 daily candles stored** in 20 gzip JSON archive pages on existing EC2 EBS, across five NSE current tokens: RELIANCE-EQ 4,955; TCS-EQ 4,954; INFY-EQ 4,955; HDFCBANK-EQ 4,954; ICICIBANK-EQ 4,954. Timestamp range requested: 2006-11-10 through 2026-11-10. Independently verified historic listing episodes and official exchange-session completeness are **not** established. No raw candle data was posted to GitHub or made available to Mintick Mind models.

Second continuation attempt: [GitHub job 129964417109](https://github.com/operationReact/AiTradingSystem/actions/runs/42854673729/job/129964417109), triggered by `fa4bcc3141938f1993f91a2ebdd449a49f6b6aac`. When this change was developed, GitHub continued to report SSM command in progress after 22:00 IST nightly stop. **Do not treat additional candles as retained without a successful completed job or a private hash/manifest audit.** Existing atomic page files should survive interruption, subject to disk durability and host reaccess.

## Problem and safe adjustment

Prior deterministic next-stock picker would repeatedly choose the first unfinished symbol if a provider rejected a historic token/date range. In a large 2,000+ active equity universe that could block progress on every other stock indefinitely, and repeated 429 attempts could harm account access.

New offline-only mechanisms added to the private collector:

- `page_failure` in the existing private SQLite manifest records *only* exchange/current token/date window, a categorical provider reason, attempt count (1–5) and the next eligible retry timestamp. No secrets or OHLCV.
- A failed window is **not** saved as an empty OHLCV response and is not promoted to a complete session. Temporary errors back off exponentially from 30 minutes up to 24 hours; a fifth failure quarantines that window for manual review. The selector skips blocked stocks and proceeds with other valid active NSE `-EQ` symbols.
- Provider HTTP 429 creates a separate **provider-wide 60-minute cooldown**. An owner script run during cooldown makes no further historical requests; it outputs only summary totals and the pause marker. It remains locked per instance, capped at five equities / 20 provider reads, and >=10s between requests.
- Immutable successful file archives and SQLite SHA256 page manifests remain intact. Runs print non-sensitive aggregate counts of saved pages/bars, temporarily failing windows and permanently quarantined windows. The pre-existing GitHub log allowlist does not expose new reason/cooldown labels or all new symbols, so diagnosis requiring new detail uses a separate controlled private inspection, not unfiltered SSM stderr.
- Existing `on: push` **exact sole-file dev trigger** remains unchanged. Neither PR tests nor merge initiate real AWS, market-data or order calls. The existing nightly EC2 stop schedule and cross-workflow concurrency lock remain unchanged.

## Separate further work

1. After confirming the second download's SSM outcome and EC2 running, conduct a private bounded manifest/file-hash inventory and reconstruct actual saved total. Do not infer row counts from scheduled API calls.
2. Before enabling unattended repetition, independently review account limits, root-disk usage, portal login sessions and exact AWS/GitHub costs. No new automatic schedule is introduced by this PR.
3. Correct survivorship bias and point-in-time symbol/token mapping, corporate action price bases, exchange holiday evidence and the other required source domains. Personal unadjusted OHLCV from active 2026 symbols is not sufficient to mark 18 Mintick Mind models data-ready.

## Explicit security boundaries

No public website endpoint, customer data distribution, data licensing claim, automatic trading, or shared commercial V77/V82 source promotion. Existing AWS credentials remain in SSM and never enter GitHub logs or SQLite rows.

# MintTick read-only mutual-fund opportunity alerts

User decision (2026-11-18): **NO automatic MF purchases, no bank payment, no SIP mandates.**
The prior draft auto-invest PR #1420 was closed without merge. This feature is strictly
an AMFI-sourced mutual-fund NAV research watchlist plus in-app, dated review alerts.

## Initial 9-fund test universe
AMFI daily NAV source: https://portal.amfiindia.com/spages/NAVAll.txt
AMFI NAV download listing: https://www.amfiindia.com/net-asset-value/nav-download

| AMFI code | ISIN | Direct Growth scheme | Category | NAV dip trigger / prior 20 observations |
|---|---|---|---|
| 120716 | INF789F01XA0 | UTI Nifty 50 Index Fund | Nifty 50 | 4% |
| 120684 | INF109K01Y80 | ICICI Prudential Nifty Next 50 Index Fund | Next 50 | 6% |
| 122639 | INF879O01027 | Parag Parikh Flexi Cap Fund | Flexi | 5% |
| 118834 | INF769K01BI1 | Mirae Asset Large & Midcap Fund | Large & mid | 7% |
| 118989 | INF179K01XQ0 | HDFC Mid Cap Fund | Mid | 8% |
| 118778 | INF204K01K15 | Nippon India Small Cap Fund | Small | 10% |
| 119835 | INF200K01RA0 | SBI CONTRA FUND | Contra | 6% |
| 118968 | INF179K01WA6 | HDFC Balanced Advantage Fund | Dynamic asset allocation | 4% |
| 120197 | INF109K01Q49 | ICICI Prudential Liquid Fund - | Liquid | No dip alert |

Scheme IDs and options were checked against AMFI dated 17-Nov-2026. This is a research
test selection only. Fund inclusion does not certify current fund quality,
investment suitability, liquidity, expense ratio, or ranking.

## Implementation

- `GET /api/customer/mutual-funds/v1/board`: verified CUSTOMER read-only dashboard,
  source status, nine funds, recent dated signal history; never writes order/payment state.
- `POST /api/admin/mutual-funds/refresh`: ADMIN or OPERATOR manual official NAV refresh.
- `MINTTICK_MF_ALERTS_ENABLED=false` and `MINTTICK_MF_HISTORY_ENABLED=false`
  by default: only enable source jobs after confirming permitted use,
  network access, deployment, and monitoring.
- Nightly AMFI NAV read at 21:45 Asia/Kolkata, 9 exact scheme identities, typed
  direct-growth options, valid non-future/non-stale NAV, no partial batch accepted.
- 22 distinct NAV observations required before any signal; alerts only when
  NAV falls past a defined drawdown from the previous 20 observed sessions.
  A second alert only at the deeper (2x) threshold. Existing data never
  silently becomes live if feed stale.
- V98 adds an opt-in, checkpointed historical NAV importer. Each window covers at most
  seven days; official AMFI history has a different column order. Incomplete,
  malformed, out-of-window or wrong-ISIN rows are rejected. Backfill begins
  56 days before initial database migration and advances one seven-day window
  per scheduled step. This history endpoint has not yet passed a deployed
  network verification, so first launch still truthfully shows WARMING_UP.
  Never seed production with illustrative NAV sequences.
- Alert suggested amount is a fixed SMALL illustrative number per category
  (₹250–₹1,000), **not** personalized advice or order instruction. User still
  decides amount and investment manually. NAV is not intraday price, and
  a fall from NAV peak is not proof of intrinsic undervaluation.
- Alert UI is in Customer More > Mutual Funds (mobile) and desktop left rail.
  No fabricated charts, order button, auto-transfer or push notification claims.
  Alert feed is an **in-app view**, not OS push (push would need separate
  consent, delivery tokens and infrastructure).

## To deliver live visible alerts

1. Merge passing PR to dev, deploy backend migration and Flutter release.
2. Verify server can fetch `portal.amfiindia.com` under applicable usage rights;
   set `MINTTICK_MF_ALERTS_ENABLED=true`, confirm first scheduled/operator refresh.
3. After confirming AMFI historical source and usage rights in deployed UAT,
   set `MINTTICK_MF_HISTORY_ENABLED=true` for one seven-day import every
   15 minutes until the initial 56-day window is complete (or invoke the
   authenticated operator step endpoint). Inspect
   `GET /api/admin/mutual-funds/backfill` for honest failures and progress.
   Full history recovery has **not** yet been proven against the live source.
4. Prove consecutive NAV events, duplicate ingestion, no stale/partial feed,
   and threshold-crossing signal dates in deployed API.
5. Consider adding risk-adjusted rolling returns, drawdown recovery, expense
   ratio, AUM, tracking error for index funds and market benchmark comparisons
   before using the model for decisions. Public personalized investment advice
   can have SEBI registration/advertising requirements; this is research only.

No payment credentials, brokerage trading authority or mutual-fund investment
permission are required for read-only NAV research.

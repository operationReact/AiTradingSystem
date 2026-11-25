# MintTick Mind intraday automation plan

Status date: 2026-11-26  
Delivery boundary: intraday PAPER first; no LIVE order authority is implied.

## Objective

Automate the repeatable intraday workflow: session context, instrument screening, setup detection, risk/execution checks, position management, and evidence-based review. The 232-entry strategy catalog is not the intraday backlog; entries must be classified by horizon and instrument before they are selected.

## Strategy delivery order

| Priority | Setup family | Candidate setups | Notes |
|---|---|---|---|
| P0 | Shared intraday controls | Session/calendar, completed-candle identity and freshness, live quote freshness/divergence, liquidity/spread, position ownership, risk sizing, kill switch, idempotency, exit and square-off | Shared by every setup; keep fail-closed. |
| P1 | Opening breakout | Opening Range Breakout (ORB) | Wired into Mind PAPER; merged to dev in PR #2033. Walk-forward certification and live authorization remain separate. |
| P2 | VWAP trend/momentum | VWAP momentum, reclaim, rejection | First next PAPER integration. Keep scanner evaluator distinct from the catalog's anchored-VWAP/reclaim entries until the rule mapping is exact. |
| P3 | Trend continuation | 20-bar SMA reclaim (existing scanner rule), EMA pullback as a separate future rule, intraday trend continuation | Use a higher-timeframe context filter and avoid duplicate exposure across correlated names. |
| P4 | Participation breakout | Volume/range expansion, consolidation breakout, previous-day level break | Volume Breakout is now wired in the guarded PAPER cycle in PR #2038; require relative-volume, spread, and post-cost slippage gates. |
| P5 | Gap setups | Gap continuation and gap-fill reversal | Separate continuation and fade hypotheses; include event/news and gap-size context. |
| P6 | Mean reversion | VWAP/band reversion, RSI reversal, support/resistance bounce | Permit only under range-compatible regime; abstain in strong trend conditions. |
| P7 | Failure/reversal | Failed breakout/breakdown, liquidity sweep and reclaim | Require confirmation and a predefined invalidation level. |
| P8 | Short-side cash setups | Breakdown, failed-breakout short, VWAP rejection, bearish momentum | Treat short entry and exit rules as a distinct validated path. |
| P9 | F&O intraday | Underlying signal, futures/options contract selection, defined-risk structures, margin/Greeks/expiry controls | Separate readiness track; do not translate a cash signal directly into an options order. |

## End-to-end workflow to automate

1. Pre-market: trading calendar, prior-session levels, gap/news/event context, instrument eligibility, and data health.
2. During market: market/sector regime, relative strength, trend/range/volatility, volume and liquidity screen.
3. Evaluate only eligible setups; record inputs, exact rule version, reasons, and abstentions.
4. Before PAPER entry: validate completed bars and source, current broker quote, session, spread, sizing, risk limits, duplicate/overlapping positions, and account kill switch.
5. Manage positions from persisted stop/target/trailing/time rules; exits remain available when new entry data is blocked.
6. Reconcile orders, virtual cash, positions, and broker state; flatten by configured session close.
7. Review after costs: expectancy, drawdown, slippage, fill rate, regime breakdown, and out-of-sample results.

## Promotion gates

- Each setup must have deterministic unit and integration coverage, including no-signal, stale/invalid data, idempotency, position ownership, stop/target exits, and PAPER-only order assertions.
- Backtests must use point-in-time inputs and realistic commissions, taxes, spread, slippage, and rejected/partial orders; use out-of-sample and walk-forward evaluation.
- Require a meaningful sample per setup and regime; report uncertainty and abstain where evidence is weak.
- Run live-data PAPER/shadow monitoring and compare quote/candle parity before considering live order wiring.
- LIVE requires a separate explicit authorization and independent broker, account, order, reconciliation, kill-switch, and deployment review.

## Current implementation checkpoint

- ORB is integrated into the Mind PAPER cycle on `dev`; PR #2033 passed its listed CI workflows before merge.
- Existing scanner registry contains ORB, volume breakout, moving-average reclaim, VWAP momentum, and RSI reversal evaluators. Registry presence does not mean Mind PAPER integration or performance certification.
- Mind PAPER supports SMA, ORB, and VWAP Momentum; moving-average reclaim is under review in PR #2037; Volume Breakout is under review in PR #2038.
- The existing MovingAverageReclaimStrategy is a 20-bar SMA reclaim, not a 20 EMA pullback. Keep its catalog mapping pending until the rule is an exact match.
- Cash-equity intraday and F&O readiness are tracked separately.

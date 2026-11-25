# STR-177 — Futures Mean Reversion (research-only)

**Catalog:** `strategy.STR-177`, eighth of 43 dedicated F&O strategies. **Current manifest status:** `DATA_BLOCKED`. The initial implementation is a pure Java 21 as-of evaluator and fixture-based tests; there is no broker feed integration, app/API endpoint, storage, PAPER action or LIVE execution.

## What it detects

Unlike STR-176 Futures Momentum (continuation breakouts), STR-177 asks whether a **recent futures price excursion has begun reverting toward its earlier trading range**, without already recovering all the way to that range's center. One extreme candle alone is insufficient.

Input consists of ten **completed and separately source-proven** exchange sessions for the **same listed NSE_FO futures contract**:

1. **Baseline:** the first **eight** sessions' verified closing prices. Calculate their arithmetic mean and *population* standard deviation using those eight closes **only**. Require a nontrivial baseline standard deviation.
2. **Excursion:** ninth session closes far enough from that prior mean (`z = [close_9 − mean_1..8] / std_1..8`), above or below an explicit minimum absolute z-score.
3. **Partial recovery:** tenth completed session close moves **toward**, but remains on the excursion side of, the prior baseline mean. The move off the ninth-session extreme must exceed the configured reversal threshold.
4. **Possible residual research opportunity:** distance from tenth close back to the baseline mean, expressed in basis points, minus explicit estimated round-trip costs, exceeds an explicit research hurdle. This is a **distance heuristic**, not realized or guaranteed P&L.
5. **Participation and quality:** latest session volume must meet a minimum relative to average baseline volume, latest volume/OI participation floor, bounded OHLC range and close/OI step changes.

Results: `LOW_EXTREME_REBOUND`, `HIGH_EXTREME_PULLBACK`, or `NONE`. These are **price-shape observations**, not recommended buy or sell orders. A failed limit returns `NO_SIGNAL`, missing authorization `BLOCKED_RIGHTS`, and incomplete/unverifiable data `BLOCKED_DATA` with a stable reason code.

## Input and as-of safety

- Same real provider-verified NSE futures instrument key for **all ten** rows, genuine listed-from/expiry dates, source-listed lot size, exact canonical NSE underlying. At evaluation, expiry must still be 7–120 calendar days away. Never stitch near and next expiry contracts into a continuous, mislabeled series.
- A licensed, source-verified normalized record per session: OHLC, traded volume, strictly positive OI in the **same underlying units**, independent `ohlcvFirstObservedAt` and `oiFirstObservedAt`, completed-session verification and broker source.
- Both first-known timestamps must be at or after the effective-dated **15:30/15:40/15:45 IST derivatives** session close, no later than the supplied evaluation `asOf`, and within a bounded publication window. The *upstream authorized adapter* must preserve true original first-seen timestamps from live/point-in-time archives. Retrospectively downloaded historical values may not be treated as having existed at the earlier close.
- Chronologically ordered weekday exchange sessions with no duplicates, excessive calendar gaps, future-known data, contract switching, mixed source, negative/zero OI, invalid prices or unsupported rights. Actual NSE holiday/special-session calendar verification and corporate-action normalization are **outside the pure evaluator** and still required.
- Separate verified internal research use rights for OHLCV, OI and contract discovery. Customer display, data retention/model training, PAPER trading and F&O LIVE execution are all separate permissions.

## Test fixture thresholds (not production calibration)

The 21 deterministic JUnit tests use explicit fixture values: baseline minimum standard deviation **25 bps**, minimum ninth-session deviation **2.00 sigma**, minimum off-extreme recovery **60 bps**, minimum cost-adjusted residual distance **100 bps**, assumed full round-trip trading costs **25 bps**, minimum latest volume / baseline average **90%**, latest volume / OI **15%**, maximum single-session close move **1000 bps**, daily OHLC range **900 bps**, maximum single-session OI change **5000 bps**. **These thresholds do not establish an edge, forward returns, trading viability or risk-adjusted profitability.**

The regression suite exercises both reversion directions, non-extreme and nonreversing paths, already-crossed mean, slippage/fee and minimum reversal hurdles, thin volume and OI-normalized participation, zero baseline variance, rights, contract identity/listing/expiry, feed mismatch, bad OHLC and bad OI, future-known observations, outliers, duplicate/nonconsecutive history, stale data, invalid threshold configuration and deterministic nonexecution.

## Next steps before source readiness and trading

1. Connect to the single [rights-gated canonical F&O data pipeline](../architecture/fno-canonical-strategy-feed.md) with true source receipts, a source-listed contract, actual *first-observed* OHLCV/OI timestamps and NSE session-calendar facts.
2. Backtest **as of each historical decision**, on multiple real underlyings and expiries, without mixing monthly contracts, and include split/corporate-action/rollover and event-driven regimes.
3. Optimize thresholds only inside an out-of-sample/walk-forward framework, incorporating costs, real bid/ask liquidity, slippage and adverse-price continuation risks; document stop/exit and position sizing only after separate risk reviews.
4. Validate separately authorized **PAPER-only**, auditable strategy/runtime behavior before considering account-specific LIVE permissions.

Every evaluator decision enforces `executable=false`, `orderPlaced=false` and `liveTradeAuthorized=false`. No Flutter UI or app deployment is included; an evaluator passing CI is not a live strategy.

## Direct runtime integration — 21 November 2026

This strategy now has an explicit `FnoFuturesResearchService.evaluateAll`
call to **its own**
`FnoFuturesMeanReversionV1.evaluate`; it is not a generic crossover alias.
It uses the *same* read-only
`FnoVerifiedCompletedOhlcvOiStore` and V102 verified source boundary as
STR-176, avoiding another independent broker data downloader or WebSocket.

The ten most recent completed sessions must belong to a single current
NIFTY NSE_FO future (near, or far if near expiry is inside seven days).
Each V102 record must carry consistent instrument/underlying/listed-from/
expiry/lot identity, OHLC, volume/OI normalized to underlying units,
separate original publication times for completed OHLCV and finalized OI,
and a provider-specific independently verified derivatives calendar.
Missing trading sessions, duplicate dates, mixed sources, mismatched
expiry/lot, stale instrument master and unavailable source rows are blocked.

Research-use rights must be independently VERIFIED, currently valid
and scoped to the exact provider/dataset for both final OHLCV and final OI;
captured market data and all original first-known times must precede
the evaluation instant. The system never claims historical data was
available at exchange close merely because it was imported later.
The research flag
`minttick.fno-research.completed-ohlcv-research-rights-verified`
**defaults false**. V102 has no seeded bars; no real-source completeness is
presumed.

Session close is now effective-dated (15:40 IST from 2026-08-03,
15:45 IST for non-expiring contracts from 2026-12-07); a 15:30
bar cannot be represented as finalized after the change.

**Readiness:** eighth direct research evaluator path, still no independently
verified real-source E2E, PAPER or LIVE execution. The threshold set
25/200/60/100/25/9000/1500/1000/900/5000 remains deterministic
fixture calibration, not observed profitability. Real provider receipts,
10-session replay across expiries, zero-lookahead tests, fees/slippage
stress, broker reconciliation, risk controls and separate trading rights
are required before operational use.

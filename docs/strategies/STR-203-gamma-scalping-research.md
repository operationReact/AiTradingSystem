# STR-203 — Gamma Scalping Research (Strategy 34/43)

**State: `DATA_BLOCKED`.** This is a deterministic, entirely **retrospective, NON-EXECUTABLE** study of a long-gamma delta-rebalancing path: buy a verified same-strike NIFTY CE and PE, hypothetically rebalance a separately listed NIFTY futures contract against the pair's changing net delta. It is **not** a real broker position, fills ledger, strategy forecast, proven positive expectancy, trading signal or margin limit.

## True instruments and Greek units

- Buy **one independently listed CE and one independently listed PE**, verified by source, same true NIFTY underlying, same actual strike/expiry, matching official option lot and tick, independently verified historical listing date.
- Model futures hedges using an independently **source-listed real NIFTY futures contract** with its OWN documented official contract lot, tick and expiry. The index itself is **not directly tradable** and must **never** be used as a fake spot hedge fill.
- Each source point includes the genuine synchronized NIFTY index, index futures bid/ask, CE and PE bid/ask, **per-underlying-unit call/put delta**, positive gamma in option-value currency per index-point-squared, negative (long-option) theta in **option-premium points/day**, **annualized actual source option IV percentage**, authentic option open interest, genuine futures bid/ask available depth, source exchange timestamp and original source-first-known timestamp.
- This research-only version requires a verified intraday observational path of at least three real snapshots, chronologically spaced within a configurable maximum sampling gap; all must be source-consistent, within normal NSE market hours, point-in-time truthful, and latest observation sufficiently fresh at classification time. It requires 35+ independently verified genuine NIFTY completed daily close observations already published before the first market snapshot.
- Separate research rights for official index, option-chain, actual provider delta/gamma/theta/IV, listed futures quotes, and original completed historical prices. Missing rights, authentic Greeks/IV, quote depth or original timestamps **fail closed**.

## Explicit hindsight-only approximation

For long-option matching option lot `Qopt`, futures lot `Qfuture`, and each time `t`:

- `netOptionDeltaUnits(t) = Qopt × (callDelta(t) + putDelta(t))`
- `hypotheticalTargetFuturesLots(t) = roundHalfAwayFromZero[−netOptionDeltaUnits(t)/Qfuture]`
- `futuresRebalanceLots(t) = target(t) − target(t−1)`. This explicitly models **integer futures lots**, including no-op delta changes and residual exposure. It is not a broker order.
- Approximate gamma capture on each **already observed** interval: `0.5 × Qopt × average(callGamma+putGamma) × [indexSpot(t)−indexSpot(t−1)]²`. Both option gamma estimates must be originally observed and source verified, not invented from a return series.
- Approximate theta drag: `−Qopt × average(callThetaPerDay + putThetaPerDay) × (elapsedSeconds / 86400)`.
- Estimated futures hedge transaction cost counts **actual signed hypothetical lot changes** plus a final hypothetical futures close. Each assumed execution uses the **real source futures bid for sales or ask for purchases**, applies independently configured side-specific slippage, calculates cost relative to the source mid, and adds **nonzero modeled fees per hedge order**. Real futures basis, cash flows, fill latency, funding and realized mark-to-market are **not** supplied.
- `approximateNetGammaThetaAfterHedgeCosts = gammaCaptureApproximation − thetaDragEstimate − hypotheticalFuturesCostEstimate`.
- A research candidate requires **actual historical hedge-target changes**, positive net approximate capture and a minimum configured capture-to-cost multiple. All other conditions return `NO_SIGNAL`. **Even a positive estimated capture is not realized P&L, an ex-ante return forecast, or permission to enter a long straddle or trade futures.**

### What this deliberately does not claim

Gamma scalping actually requires buying options at their executable asks, funding/holding through the path, managing the delta hedge via real futures fills and mark-to-market, respecting broker position margins, carry/basis, futures/option lot differences, time decay, skew and changing volatility, the market's bid/ask and fees. The isolated gamma-vs-theta-vs-hedge-cost estimate **excludes the initial option premium, final liquidating option marks, actual fills, futures variation-margin cash flow, path-dependent Greeks between discrete observations, overnight gaps and margin liquidation**. It is therefore **not an economically complete strategy P&L or a backtest**.

Any apparent `RESEARCH_CANDIDATE` is a *retrospective property of an already-seen price/Greeks path*, not an entry/exit or a forward predictor. Source IV and Greeks are not auto-synthesized from price changes. Genuine source licensed rights are required even when market-broker credentials exist.

All returned decision states (`BLOCKED_RIGHTS`, `BLOCKED_DATA`, `NO_SIGNAL`, `RESEARCH_CANDIDATE`) have `retrospectiveOnly=true`, `actualOptionOrHedgeFillsObserved=false`, `executable=false`, `orderPlaced=false` and `liveTradeAuthorized=false`. Nothing is sent to option or futures broker execution APIs.

## Unit tests and still missing acceptance

14 deterministic JUnit scenarios cover an original-timestamped four-snapshot price path and integer futures hedges, correct approximate gamma capture `2060` in **fictional** rupee-unit fixtures, estimated theta and trading fees, five distinct research rights, same-strike CE/PE and independently listed futures instruments, completed historical closes and original first-known times, authentic same-provider samples, true signed delta/positive gamma/negative theta/annualized IV, stale or future-known ticks, spot/futures liquidity and spreads, no hedge-rebalance, excessive modeled brokerage cost, insufficient sample history, invalid assumptions and strict never-execute invariants. **Numerical NIFTY values around 100 and strikes are fictitious test fixtures**, not real NSE markets, profitable opportunities or evidence of licensed live data.

**Unresolved before PAPER/LIVE:** genuine licensed NIFTY options **bid/ask and original Greeks histories**; separately authenticated NIFTY futures instrument and source quote depth; per-instrument lot/expiry/calendar, real futures basis/carry and independent data rights; actual fill-level multi-leg simulation including bought-option initial and final marks, broker funding, hedging cash ledger, fees/taxes and futures variation margin; genuine original-first-known out-of-sample path research; tested broker permissions, room risk concentration, dynamic Greeks and kill switches, accepted PAPER execution ledger/UI, and independently approved LIVE option **and** futures trading rights. Catalog state stays `DATA_BLOCKED`.

Related: [STR-202 Volatility Crush Research](STR-202-volatility-crush-research.md).

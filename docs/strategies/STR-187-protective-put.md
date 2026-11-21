# STR-187 — Protective Put (research-only)

**Catalog:** `strategy.STR-187`, Strategy 18/43. **State: DATA_BLOCKED**, even when code and unit tests pass.

## Distinct economic structure

A **protective put** requires an *already-owned, source-reconciled deliverable equity lot* and a corresponding listed **same-equity PE** contract. It is not a naked long put, an index put against an unrelated stock, a paper-only simulated holding, or permission to create the underlying holding.

This evaluator restricts the research candidate to **exactly one settled, broker-reconciled stock lot** matching one listed, physically compatible PE lot. This intentionally excludes partial lots and NIFTY-index proxy hedges; different hedge ratios and index basis exposure require a distinct explicitly modeled strategy. The stock remains owned; the model considers hypothetical purchasing one put.

The protective risk trigger is a new **20-completed-session drawdown from a prior peak**, not the bullish/bearish EMA crossover used in previous strategies. The peak is computed from the *20 completed sessions before the latest*; the latest completed close is compared to that peak. Underlying candles and source-first-known publication must not look into the future.

## Hypothetical expiry calculation

At a user-explicit hypothetical downside scenario:

- `putPaid/share = source PE ask × (1 + assumed buy slippage / 10000)`
- `premiumAndFees/lot = putPaid/share × actual contract lotSize + modeled all-in Indian transaction/expiry fees`
- `scenarioSpot = spot × (1 − hypothetical downside bps / 10000)`
- `unhedgedScenarioValue = scenarioSpot × settledShares`
- `protectedScenarioValue = scenarioSpot × settledShares + max(strike − scenarioSpot, 0) × putLotSize − premiumAndFees`
- `scenarioBenefit = protectedScenarioValue − unhedgedScenarioValue`
- `theoreticalProtectedExpiryFloor = strike × lotSize − premiumAndFees` assuming both owned shares and valid put remain continuously held together through expiry.

A candidate requires an observed enough drawdown, the full put premium and fees inside the research budget, and positive minimum scenario benefit net of costs. This is **not** a probability model, a promised floor during trading, a broker fill, or proof against all corporate-action/settlement/tax risks. Stock acquisition cost is preserved as evidence but the model compares value *from the current spot*, not historical realized P&L.

## Fail-closed prerequisites

- One broker-reconciled, timestamped, **settled and deliverable actual equity holding**, same broker as market quotes, exact matching lot of shares, verified original position observation no older than 5 minutes. No manufactured positions.
- Independently source-verified genuine listed `NSE_FO` **PE** contract underlying the exact `NSE_EQ` equity, genuine strike/lot/expiry/tick and physically matched deliverable, illustrative 3–45 calendar days to expiry. No misuse of an NIFTY option as a stock hedge.
- 35–512 legitimate ascending completed daily 15:30 IST equity closes, provider consistency, genuine exchange and first-known publication times, validated weekdays. The upstream adapter must additionally attest actual NSE holiday/special-session calendar and corporate actions.
- Same-provider equity and PE exchange-time quotes aged <=180 seconds, exchange skew <=90 seconds, original first-known timestamps and completed bars already known; verified noncrossed bid/ask, real ask-depth and OI in lots, bounded spread and strike distance.
- Independent licensed rights to research underlying, options, contract discovery **and broker-held portfolio data**. Explicit nonzero transaction taxes/fees, premium budget and hypothetical downside assumption.

All outputs are one of `BLOCKED_RIGHTS`, `BLOCKED_DATA`, `NO_SIGNAL`, `RESEARCH_CANDIDATE`; all have `executable=false`, `orderPlaced=false`, `liveTradeAuthorized=false`.

## Validation and blockers

12 deterministic JUnit methods cover a properly owned PE research candidate, no drawdown, missing rights or portfolio reconciliation, partial lot and wrong index basis, CE and missing physical match, stale holdings, stale/future quote, cross-provider/illiquid options, future-known and duplicate candles, premium budget, weak hedge downside scenario, invalid fee assumptions, deterministic non-execution. These are synthetic **unit-test fixtures only**; no fake results are inserted into app market data.

**Open:** rights-authorized real NSE equity/option feeds and original publication times, valid reconciliation and ownership semantics, true contract/lot/delivery/corporate action details, Indian taxes/expiry/broker margin rules, no-lookahead OOS and walk-forward performance, real PAPER approval and Flutter acceptance; separately audited LIVE permissions and order handling. This commit does **not** connect to providers or turn on actual options trading.

See [STR-186](STR-186-bear-call-credit-spread.md), [canonical F&O research feed](../architecture/fno-canonical-strategy-feed.md).


## Broker-owned stock source integration — 21 November 2026

`FnoOwnedEquityOptionsResearchService` now invokes the full, distinct
`FnoProtectivePutV1.evaluate` method using 35+ source-first-known
completed `NSE_EQ` close sessions and one **actually broker-reconciled,
settled, unpledged, deliverable, one-lot** shareholding matching an
independently source-listed same-equity `NSE_FO` PE.

The new V109 EMPTY custody has one verified equity history table
(official NSE equity session calendar and independently active provider
research rights) and a separate short-retention broker-account-scoped
ownership/option table, with exact share count, acquisition cost,
first-observed holdings, physical settlement mapping, original NSE
stock/option ticks, bid/ask depth/OI in lots, independent fees/tax
and premium-risk budget evidence. Both tables are immutable before
retention expiry. No broker funds or holdings are seeded.

Source rights flags, broker account scope, stock key and explicit PE
instrument key are all default-denied/unconfigured. A broker login,
paper position, linked watchlist or present index price is NOT proof
of settled delivery-eligible shares. The calculated hypothetical
expiry downside benefit is not a forecast, an order, PAPER fill, or
LIVE NSE hedging approval. Real-source E2E remains unverified.

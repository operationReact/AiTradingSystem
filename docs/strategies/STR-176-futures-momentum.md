# STR-176 — Futures Momentum (research-only)

**Catalog:** `strategy.STR-176` (MintTick Mind F&O strategy 7 of 43). **Status: DATA_BLOCKED** until licensed data source, completed NSE trading sessions, historical replay and PAPER runtime evidence are proven. The first implementation is a pure Java 21 evaluator, not an app-connected strategy or an order authorization.

## Hypothesis and scope

Futures prices moving **above the highest high of the preceding six completed sessions**, or **below the lowest low of the preceding six sessions**, may indicate short-horizon momentum. The seventh session must also pass a recent closing-price consistency test, meaningful trading-volume participation, OI/source quality checks, and an illustrative cost hurdle.

The evaluator classifies **UPWARD_BREAKOUT**, **DOWNWARD_BREAKOUT**, or **NONE** for research. It does not infer guaranteed returns, an executable order, precise entry/stop/target, or a broker-authorized signal. Open interest is used for participation/data quality, **not** as a standalone indicator of bullish/bearish positioning. Independent strategy families STR-173 and STR-174 handle OI-only trend and price/OI divergence.

## Required source and contract proof

- One **source-listed, provider-verified NSE_FO futures instrument** with a matching canonical NSE_EQ or NSE_INDEX underlying, provider-confirmed listing date and expiry, and lot size. The same exact futures key applies to all seven observations. Remaining time to expiry must be 7–120 calendar days.
- **Seven chronologically ordered, verified completed NSE trading sessions**, never merged across rollover or expiry. Basic sanity checks enforce ordered weekdays and calendar gaps of at most five days; a separate upstream adapter must verify the official NSE holiday/special-session calendar and actual session continuity.
- Each session supplies verified **OHLC** in INR per underlying unit (high >= open/close >= low and positive prices), normalized traded volume in underlying units, positive normalized OI, trusted source identity, original publication timestamps for **OHLCV and OI separately**, and a verified session-completeness flag.
- Session-end OHLCV and OI first-observed timestamps must be **at or after the applicable 15:30/15:40/15:45 IST derivatives session close**, no more than 36 hours after close and never after the supplied evaluation `asOf`. Downloading historical bars later does **not** prove they were available on the original trading date; a properly preserved first-known timestamp is necessary.
- Verified internal research-use rights for OHLCV, OI and contract discovery separately. Broker F&O account rights, market-data display rights and LIVE order permissions are independent gates.

## Deterministic classification

- Define `priorHigh = MAX(high of sessions 1..6)` and `priorLow = MIN(low of sessions 1..6)`; evaluate **session 7 finalized close only after its actual publication**.
- `upwardBreakoutBps = 10,000 × (latestClose − priorHigh) / priorHigh`.
- `downwardBreakoutBps = 10,000 × (priorLow − latestClose) / priorLow`.
- Examine the **last three consecutive close-to-close steps** (session 4→5, 5→6, 6→7). For upward momentum, at least the configured 2–3 steps must rise and none may fall; for downward momentum the reverse applies. Flat steps may be neutral.
- The last three sessions' total **traded-volume-to-first-three-sessions volume** must exceed the configured participation ratio; latest session's **traded volume / final OI** must also pass an explicit floor. Zero baseline volume is invalid.
- Block suspicious OHLC range or excessive single-session closing price changes as **data outliers** (these are conservative guardrails, not a formal volatility forecast).
- Check an explicit **gross breakout threshold**, then subtract estimated all-in round-trip transaction costs in bps and require the residual breakout margin to exceed an explicit research hurdle. This is a *signal-quality heuristic*, not a realizable P&L calculation.

**Illustrative regression-fixture thresholds, NOT trading recommendations:** 100 bps minimum breakout, 2 of the last 3 matching close steps, recent/baseline volume ratio >= 11,000 bps, latest volume/OI >= 2,000 bps, maximum single close-to-close move 2,500 bps, maximum daily high–low range 800 bps, illustrative costs 20 bps and cost-adjusted breakout hurdle 30 bps. Production thresholds must be calibrated and validated rather than copied from the test fixture.

## Failure and execution contract

- `BLOCKED_RIGHTS`: missing authorized source use or contract discovery.
- `BLOCKED_DATA`: invalid contract/listing/expiry, incomplete/nonsequential sessions, mismatch of futures key or provider, unverified OHLCV or OI first-known timestamp, bad OHLC/volume/OI, abnormal price bars or stale history.
- `NO_SIGNAL`: no corroborated breakout, weak participation, or after-cost research margin below hurdle.
- `RESEARCH_CANDIDATE`: a strictly as-of breakout observation, never permission to place an order.

Every `Decision` has `executable=false`, `orderPlaced=false`, `liveTradeAuthorized=false`. There is **no** raw provider WebSocket, account session, SSM credential use, server endpoint, database persistence, PAP​ER order or LIVE execution integration in this slice.

## Validation and remaining requirements

20 JUnit regression tests cover upward/downward cases, neutral and contradictory close steps, no breakout, low recent volume or OI-normalized volume, illustrative fees, daily outliers, individual rights, listed-contract identity and expiry, provider/contract switching, invalid OHLC, final-data publication lookahead, OI quality, missing or reordered sessions, invalid thresholds and determinism.

Before declaring runtime/production readiness: integrate the rights-verified [canonical F&O feed](../architecture/fno-canonical-strategy-feed.md) with genuine exchange session/contract and original as-of publication evidence; replay actual F&O historic bars as-of without lookahead; calibrate thresholds by underlying/regime and after-cost out-of-sample walk-forward analysis; execute only separately authorized **PAPER** trials with explicit risk and reconciled results. A profitable strategy is not established by passing these unit tests.

**Client impact:** backend-only evaluator, no Flutter route/Android APK or web/iOS runtime acceptance. LIVE F&O trading rights remain unchanged.

## Source-gated runtime adapter integration — 21 November 2026

`FnoFuturesResearchService.evaluateAll` now invokes the *distinct*
`FnoFuturesMomentumV1.evaluate` call. This is a read-only research
evaluation, not an order, signal-to-order router or model-backed alpha claim.

The newly added V102 table and `FnoVerifiedCompletedOhlcvOiStore` provide an
**empty-by-default, append-only** contract for one futures contract's completed
exchange-session OHLCV and separately finalized OI. It is a reusable canonical
source boundary for subsequent STR-177 and related seven-to-ten session models;
no new redundant WebSocket is launched.

Required eligibility is independently source-verified:

- Same current-source-master NSE_FO contract, canonical underlying, expiry,
  lot and **source-confirmed listing-from** identity for all rows; strict
  single-provider chronological sessions and seven consecutive trading days.
- Genuine completed session OHLC, normalized volume and finalized OI; original
  OHLCV and OI *separate first-known times*, independent provider-source digests,
  verified NSE derivatives session-calendar custody and separate active
  `MODEL_INFERENCE` research rights for `nse_fo_completed_ohlcv` and
  `nse_fo_final_oi`.
- Strict as-of availability for prices, OI, calendar verification and source
  rights; as-of historical downloads are **not** silently backdated to the
  original exchange time. Session close now uses the applicable 15:40 IST
  from 2026-08-03 and 15:45 IST from 2026-12-07 for non-expiring contracts.
- No roll-over contract stitching, mixed providers, duplicate dates, assumed
  trading sessions, invented OI, unverified fee assumptions, executable orders,
  provider credentials, backend deployment or new APIs.
- Retention-controlled source custody, with only expired-row purge permitted.
  SQL rejects unverified or absent rights and unknown calendar evidence.

The configuration flag
`minttick.fno-research.completed-ohlcv-research-rights-verified=false`
defaults to denial. The V102 table is empty until genuine verified source
receipts are ingested by a separately reviewed source workflow; sample JUnit
data cannot populate it.

**Evidence boundary:** seven direct research evaluator paths are now wired,
but independent real-source historical E2E, PAPER and LIVE remain zero.
Calibration, fees/slippage, genuine cross-expiry no-lookahead backtesting,
data provenance attestation and real PAPER ledger reconciliation are still
required before trading use.

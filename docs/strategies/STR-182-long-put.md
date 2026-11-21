# STR-182 — Long Put (NIFTY directional; research-only)

**Catalog:** `strategy.STR-182`, Strategy 13/43. **Status:** `DATA_BLOCKED` pending rights-verified real option-chain input, replay and PAPER acceptance.

## Economic logic and scope

A long **PE** is a single-leg bearish option position with a paid premium. STR-182 reuses the source-verified, *completed daily* EMA8/EMA21 crossover engine (`FnoLongOptionCrossoverV1`) and requires a **new bearish EMA8 below EMA21 crossing**, not just an ongoing downtrend. It adds contract, source, quote, liquidity and cost-aware hypothetical *expiry* payoff gates.

No source adapter, broker order path, customer permissions, authentication credentials, SSM, deployed Flutter screen, live/paper permission flags or trade execution is changed.

## Inputs and provenance

- Verified canonical `NSE_INDEX|Nifty 50` underlying and matching source-listed `NSE_FO|...` **PE** instrument with real strike, tick size, lot size, listing date and expiry (illustrative research 3–45 calendar-day DTE).
- 35–512 completed chronological daily closes, matching source provider, independent weekday exchange session verification, true 15:30 IST exchange close and source-first-observed times no earlier than close or later than as-of and within 36 hours. Actual NSE holiday/special session attestation belongs to the source adapter; weekday verification alone is not enough.
- Same provider fresh bid/ask for the PE and underlying exchange-time snapshot (<=180 seconds at evaluation, <=90 seconds between legs), observed in 09:15–15:30 IST, with first-known timestamps and no historical forward information. The final completed candle must be known *before* both quote exchange events.
- Independently verified research rights for the index, option chain and contract discovery. Real ask depth and option OI in *lots*, maximum spread and moneyness guardrails; nonzero estimated all-in Indian statutory fees/slippage.
- Explicit modeled research capital, fraction allocated to premium at risk, and scenario downside; no price or volatility forecast is implied.

## Formula and decisions

1. Require a new **EMA8 below EMA21** bearish completed-candle crossing.
2. Hypothetical paid entry per option unit = PE ask × (1 + assumed buy slippage / 10000).
3. Modeled max loss/lot = paid entry × real contract lot size + assumed fees/lot. This is a payoff simplification, not actual broker liability, settlement, assignment or execution evidence.
4. *At expiry* breakeven (underlying units) = put strike − entry − fees/lotSize.
5. Scenario underlying at expiry = underlying snapshot × (1 − **explicit hypothetical downside bps**/10000).
6. Put expiry intrinsic/lot = max(put strike − scenario underlying, 0) × lot size.
7. Scenario net P&L = intrinsic − modeled max loss; scenario reward-risk bps = 10000 × net / modeled max loss.
8. If modeled max loss exceeds the configured capital fraction, or the selected downside scenario does not pass the research reward/risk threshold, classify `NO_SIGNAL` even with bearish technical confirmation.

Results: `BLOCKED_RIGHTS`, `BLOCKED_DATA`, `NO_SIGNAL`, or `RESEARCH_CANDIDATE`. All return `executable=false`, `orderPlaced=false`, `liveTradeAuthorized=false`. A hypothetical positive payoff **does not** predict probability of decline or guarantee profit. Actual loss of premium can reach 100%, plus fees.

## Verification

16 JUnit test methods: bullish/flat/bearish crossover, correct PE type, missing rights, expiry and contract, stale/future-known, cross-provider/wrong instrument, timestamp skew and hours, crossed and wide quote, missing depth/OI, duplicate and forward-known candles, final-candle lookahead, unfavorable downside scenario, premium-at-risk cap, missing/zero fees, bounded put intrinsic and deterministic non-execution.

Required external acceptance: licensed NSE option chain and NIFTY feed with original source publication timestamps and validated calendar, real strikes/lot/taxes/expiry and depth/OI, no-lookahead out-of-sample and walk-forward replay, authorized PAPER lifecycle and Flutter integration. Research source rights do not authorize LIVE execution.

See [canonical F&O source architecture](../architecture/fno-canonical-strategy-feed.md), [Long Call STR-181](STR-181-long-call.md).


## Distinct read-only source adapter — 21 November 2026

`FnoOptionsResearchService` now invokes its own
`FnoLongPutV1.evaluate`, independently of the STR-181 long-call path.
The lower EMA8/EMA21 crossover alone never authorizes buying a put:
the evaluator also needs 35+ source-first-known completed NIFTY daily
closes, a source-listed true `NSE_FO` **PE** contract with matching
strike/tick/expiry/lot and original-time synchronized NIFTY and PE quotes,
ask depth/open interest **in lots**, explicit tax/slippage and research
premium risk-capital evidence.

V106 is empty at deployment. Its source readers require active provider-
specific verified `MODEL_INFERENCE` rights for daily NIFTY index closes,
underlying index ticks, PE option-chain depth/OI, option instrument master
and independent Indian fees/research-capital receipts. Missing, conflicting
or forward-known records fail closed, never becoming synthetic option data.
The explicit configured `long-option-pe-instrument-key` is blank by
default; all corresponding research permission flags default to false.

The theoretical downside-at-expiry payoff is an *illustrative stress
scenario*, not a price forecast. No real independent data E2E, PAPER
or LIVE has passed. Manifest status remains `DATA_BLOCKED` pending
source rights and point-in-time broker/paper reconciliation.

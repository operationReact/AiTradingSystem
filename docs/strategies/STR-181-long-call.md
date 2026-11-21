# STR-181 — Long Call (NIFTY directional; research-only)

**Catalog:** `strategy.STR-181`, Strategy 12 of 43. **Status:** `DATA_BLOCKED` until licensed option-chain evidence, audited no-lookahead backtests and separately authorized PAPER acceptance.

## Purpose and boundaries

STR-181 examines a **long CE option** on verified `NSE_INDEX|Nifty 50` after a **new completed daily EMA8-over-EMA21 bullish crossover**. It reuses the existing `FnoLongOptionCrossoverV1` signal but adds independent option contract identity, quote provenance/freshness, spread and depth/OI gates, and an explicitly hypothetical at-expiry payoff. This is not 43 fully operating strategies, a buy recommendation, a broker order, a prediction, or a production-calibrated profitability claim.

Unlike futures STR-170–180, this is a **single-leg premium-paid option**. Paid premium plus estimated taxes and costs is the modeled maximum loss, *assuming no additional exercise/settlement liabilities*. Indian expiry settlement, actual broker charges, and extraordinary market situations require separate verification. No margin, short option, short underlying or debit spread is modeled. The evaluator never places PAPER or LIVE orders.

## Required source contract

- Verified NSE F&O **CE** contract key, matching NIFTY underlying, exact strike, tick size, listed-from, source-published expiry, and lot size. Reject puts, mismatched underlying, missing attestation and contracts outside the **illustrative 3–45 calendar-day DTE research window**; do not guess option-chain strike or expiry.
- **35–512** chronological, independently verified completed NSE weekday daily closes, exchange-close at **15:30 IST**, same authorized provider, explicit original first-known publication timestamp per session (not an ingestion timestamp), observed no more than 36h after each close. Real calendar/holiday checks must be performed by the adapter; simple weekday checks alone are not exchange calendar attestation.
- Verified synchronized, positive, noncrossed **same-provider** live underlying NIFTY price and CE bid/ask quote with exchange event times during **09:15–15:30 IST**, first-known times at or after each event, at most **180s** old at evaluation, and inter-leg skew at most **90s**.
- The last completed daily candle must have been published **before both quotes' exchange events**. Never use a candle that became available only later to decide using an earlier quote.
- Explicit call ask-side depth (lots), option OI (lots), maximum bid/ask spread and strike distance. Real broker lot units must be normalized and independently verified. Research rights for underlying, option chain and instrument discovery are independently required; display, training, history retention and execution permissions do **not** follow automatically.

## Deterministic evaluation

1. Validate rights, contract, source history, event timestamps, quote synchronization, spread, lot depth/OI, and assumptions. Missing data or permission -> `BLOCKED_RIGHTS` or `BLOCKED_DATA`, never silently substituted with fake market rows.
2. Reuse `FnoLongOptionCrossoverV1`: require **a new EMA8 cross above EMA21**, not a still-bullish trend or a bearish cross. Otherwise -> `NO_SIGNAL`.
3. Assume an **explicitly supplied illustrative** underlying upside at the option expiry date; this is **not** a forecast and is not a threshold learned from markets.
4. Per-unit assumed entry price = option ask × (1 + buy-slippage-bps/10000); modeled breakeven at expiry = strike + assumed entry + (all-in fees per lot / lot size).
5. Modeled maximum paid premium-and-fee loss per lot = (assumed entry × lot size) + explicitly supplied fees and taxes. Reject as `NO_SIGNAL` if it exceeds the assumed per-research capital allocation.
6. Hypothetical *expiry* intrinsic payoff per lot = max(0, scenario underlying at expiry − strike) × lot size. Scenario P&L = payoff − premium-and-fee loss. Scenario reward/risk = scenario P&L / modeled premium-and-fee loss. If scenario payoff is insufficient net of costs or below threshold -> `NO_SIGNAL`; otherwise `RESEARCH_CANDIDATE`.

These are *hypothetical expiry payoffs*, not calibrated expected returns, option delta/IV estimates, or reliable execution fills. A positive scenario can fail to occur; a long call can lose 100% of premium. Do not call this strategy profitable until broad out-of-sample proof.

### Test evidence

16 isolated local Java test methods cover bullish/flat/bearish outcomes, rights, verified contracts and expiry, stale/forward-known/cross-provider/mismatched quotes, time skew, session hours, crossed/wide bid-ask, absent depth/OI, duplicate/future-known bars, historical first-seen lookahead, insufficient scenario outcome, capped research capital, invalid fees and determinism. They use **explicitly constructed illustrative fixtures**, not licensed Indian market data.

## Non-implemented prerequisites

- Real, rights-verified Upstox/Angel One canonical F&O CE contract/quote adapter, source event timestamps, original per-bar publication timestamps and exchange-calendar/lot/settlement attestations.
- Transaction-tax and actual order-fill validation, broker-instrument strike/expiry provenance, IV/theta decay context, realistic gaps and settlement scenarios; separate historical as-of replay, **walk-forward and OOS** evidence.
- Separate, authorized PAPER strategy lifecycle/ledger/risk tests and customer Flutter screens; broker F&O permissions and LIVE trade consent remain unchanged.
- No broker subscription, SSM update, deployment, trading flag or order endpoint is altered by this evaluator.

See [canonical feed ADR](../architecture/fno-canonical-strategy-feed.md) and [prior directional EMA evaluator](../../backend/src/main/java/in/aitradingsystem/tradingrooms/FnoLongOptionCrossoverV1.java).


## Distinct read-only source adapter — 21 November 2026

The previous EMA8/EMA21 bullish crossover route remains only a *signal*.
A **distinct `FnoLongCallV1.evaluate` invocation** now runs through
`FnoOptionsResearchService` with point-in-time completed NIFTY closes
and one explicitly configured source-listed real CE contract. The code
does NOT manufacture instrument tokens, strikes, prices, OI, book depth,
premium-risk capacity, fee/tax amounts, or positive signal outcomes.

V106 creates **empty** provider-scoped source evidence tables for 35+
true NSE trading-session NIFTY closes, original first-known timestamps,
official exchange calendar and active independent `MODEL_INFERENCE`
index research receipts, plus exact CE instrument-master and exchange-time
index/option quotes, ask depth/OI **in lots**, reviewed fees/slippage,
research capital and a purely hypothetical expiry payoff. Four distinct
currently verified provider use-rights receipts gate the underlying tick,
listed option contract, CE option chain depth/OI and fee/risk source.
No backdating of candles acquired after a decision instant is allowed.

Both the CE instrument key and source flags default unconfigured/denied:
`long-option-ce-instrument-key`,
`nifty-daily-close-research-rights-verified`,
`long-option-quote-research-rights-verified` and
`long-option-contract-discovery-verified`, all under
`minttick.fno-research`. A local JUnit fixture candidate is NOT real
provider data, brokerage approval, traded fill, projected profit,
PAPER acceptance or LIVE order permission. The manifest stays
`DATA_BLOCKED`.

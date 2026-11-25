# STR-184 — Bear Put Debit Spread (research-only)

**Strategy 15/43**, canonical `strategy.STR-184`. Catalog status `DATA_BLOCKED`; no LIVE/PAPER trading authorization.

A **bear put debit spread** buys a higher-strike NIFTY PE and sells a lower-strike PE. Both are separately source-verified listed NSE_FO contracts on the same `NSE_INDEX|Nifty 50` with exactly the same expiry and lot. The position is directional bearish with a capped *theoretical fully-established expiry payoff*, not a guaranteed broker-fill outcome.

Research trigger: one newly completed daily EMA8 crossover **below** EMA21 from `FnoLongOptionCrossoverV1`, not a persistent downtrend or a bullish crossover. A missing permitted signal, option quote or contract may not be invented.

## Payoff

For `lowerStrike < higherStrike` and one lot each, buy the **higher PE** at its ask including assumed buy slippage and sell the **lower PE** at its bid less sell slippage.

- `netDebitUnit = longHigherPEAskAfterSlippage − shortLowerPEBidAfterSlippage`, required strictly between zero and strike width
- `maxModeledLossPerLot = netDebitUnit × lotSize + nonzero all-in two-leg fees`
- `maxModeledGainPerLot = (higherStrike − lowerStrike − netDebitUnit) × lotSize − all-in fees`
- `breakEvenAtExpiry = higherStrike − netDebitUnit − fees / lotSize`
- `maxRewardRiskBps = 10000 × maxModeledGain / maxModeledLoss`

Positive model gain must clear a documented minimum research reward-risk threshold, and the full debit plus costs must not exceed the research-capital allocation. This is a **theoretical at-expiry model** that assumes successful establishment and retention of both legs, not a trade recommendation, fill model or profit prediction. Real two-leg order fills are non-atomic, margin can differ, and Indian expiry and settlement costs must be verified separately.

## Fail-closed evidence requirements

- Independent rights for index research, option chain research and instrument discovery; no inference of display, training, historical retention, PAPER or LIVE rights
- Exactly verified CE-vs-PE type (**PE only**) and distinct ordered strikes, matching canonical NIFTY underlying and options expiry/lot, listing, tick size; illustrative 3–45 calendar days to expiry
- 35–512 original completed and source-observed 15:30 IST NSE daily index closes (chronological weekdays, source-first-known timestamps within 36h and no future-known candle); exchange calendar/holidays attested separately by the adapter
- Fresh same-provider index quote and both option bid/asks during NSE market session, individual age <=180s, three-leg skew <=90s, first-known <= evaluation time and last completed candle originally published before these quote events
- Positive noncrossed bid/ask, verified real bid/ask depth and OI in correct *lot* units, bounded spread and strike distance, explicit nonzero Indian modeled fees and slippage

All decisions are `BLOCKED_RIGHTS`, `BLOCKED_DATA`, `NO_SIGNAL` or `RESEARCH_CANDIDATE`. All expose `executable=false`, `orderPlaced=false`, `liveTradeAuthorized=false` with no connection to any order route.

## Tests and remaining blockers

11 deterministic JUnit tests cover bearish candidate and capped loss/payoff, non-bearish direction, missing research rights, type/strike/expiry/lot mispairing, missing OI/depth, stale and mismatched provider or leg quotes, historical lookahead, crossed or uneconomic debit, capital limits, fee-adjusted payoff and non-execution.

**Not completed:** licensed real option-chain data and original source timestamps, true NSE exchange calendar, live synchronized paired PE data, actual Indian fees/delivery/expiry/broker margin verification, independent no-lookahead historical and walk-forward evidence, PAPER ledger and Flutter acceptance, separately approved LIVE rights. No broker order, SSM, production deployment, customer entitlement or LIVE/PAPER flag changed.

See [STR-183](STR-183-bull-call-debit-spread.md) and [canonical F&O architecture](../architecture/fno-canonical-strategy-feed.md).


## Direct verified two-PE research adapter — 21 November 2026

`FnoDebitSpreadResearchService` now invokes
`FnoBearPutDebitSpreadV1.evaluate` on verified source history and
two distinct listed NSE_FO NIFTY put options. The actual option
construction is **long higher-strike PE at ASK**, **short lower-strike
PE at BID**, same expiry/lot and same provider. No simulated or
single-option price is accepted as both legs.

The new empty V107 custody includes original event and first-observed
timestamps for the index and both PE quotes, independent per-leg
bid/ask depth and open interest **in lots**, a verified genuine option
master digest, tax/fee/slippage model and bounded research-risk capital.
At least five approved point-in-time provider-specific research-use
receipts are needed for this three-leg (index + two options) observation,
in addition to V106 verified daily NIFTY 35+ completed closes and
official calendar. A malformed or cross-provider option/expiry,
missing exchange quote or unverifiable costs fail closed.

Both `debit-bear-lower-pe-key` and `debit-bear-upper-pe-key` under
`minttick.fno-research` are blank by default; the global debit-spread
research rights flag defaults false. The hypothetical terminal
spread payoff is not a fill, margin guarantee, realized return,
broker position, paper trade or live order. Real-source E2E,
PAPER and LIVE acceptance remain zero.

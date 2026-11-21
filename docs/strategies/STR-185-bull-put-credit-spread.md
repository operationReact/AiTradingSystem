# STR-185 — Bull Put Credit Spread (research-only)

**Catalog:** `strategy.STR-185` (Strategy 16/43). **Status: DATA_BLOCKED**. The evaluator never authorizes PAPER or LIVE execution.

## Economic model

This bullish put credit vertical **sells one higher-strike NIFTY PE** and **buys one lower-strike NIFTY PE**, with independently verified matching expiry, underlying, strike ticks and lot size. It requires a *new completed daily bullish EMA8-over-EMA21 crossover* from the shared `FnoLongOptionCrossoverV1` research engine. An ongoing uptrend is not a new signal. No broker WebSocket, feed subscription, order, API, SSM parameter, or trading flag is changed.

Use the **higher-strike PE bid** (sell) and **lower-strike PE ask** (buy), with explicit trade-side slippage:

- `netCreditPerUnit = shortHigherPutBidAfterSellSlippage - longLowerPutAskAfterBuySlippage` (must be greater than zero and strictly below strike width).
- `maxModeledGainPerLot = netCreditPerUnit × lotSize - combined nonzero two-leg fees and taxes`
- `maxModeledLossPerLot = (higherStrike - lowerStrike - netCreditPerUnit) × lotSize + combined fees`
- `breakevenAtExpiry = higherStrike - netCreditPerUnit + feesPerLot / lotSize`
- `theoreticalMaxRewardRiskBps = 10000 × maxModeledGain / maxModeledLoss`

A credit is **not free capital**: the modeled *maximum loss* must fit the research capital allocation. The theoretical bounded loss presumes **both legs fully fill** and the spread is maintained/settled together. Indian broker margin, non-atomic execution, physical or cash settlement, early liquidation, slippage, regulatory taxes and liquidation failures require independent validation. Even a bullish signal may result in the modeled maximum loss.

## Point-in-time source and integrity requirements

- Rights-verified source-listed, canonical `NSE_INDEX|Nifty 50` underlying and two genuine `NSE_FO` **PE** contracts with one expiry, identical lot size, ordered strike and source-listing metadata (illustrative 3–45 calendar days to expiry).
- 35–512 completed daily index 15:30 IST closes, chronological verified sessions, separately retained original exchange and first-known publication timestamps, provider consistency, 36-hour publication window, no forward-known bars. Weekday sanity checks do not replace official NSE holiday/special-session verification.
- Three contemporaneous quote snapshots for NIFTY, lower PE and higher PE: same provider, exchange event times in 09:15–15:30 IST, <=180 seconds age and <=90 seconds pairwise spread, original observed <= evaluation `asOf`. Last completed index candle must already have been published when the quote events occurred.
- Both puts require positive noncrossed bid/ask, verified bid and ask depth and OI measured in **lots**, bounded spread and strike distance; missing permissions or source data fail closed.
- Explicit positive all-in modeled fees and taxes, slippage for both legs, capital and per-strategy budget limit, and minimum cost-adjusted reward/risk threshold.

Returns `BLOCKED_RIGHTS`, `BLOCKED_DATA`, `NO_SIGNAL`, or `RESEARCH_CANDIDATE`. Every decision exposes `executable=false`, `orderPlaced=false`, and `liveTradeAuthorized=false`.

## Test coverage and remaining blockers

11 deterministic JUnit methods cover bullish candidate, direction reject, rights, PE/strike/lot/expiry pairing, depth/OI/crossed quotes, mismatched sources and stale leg timing, historical lookahead, invalid or excessive credit, premium/risk budget, fees, deterministic non-execution. Illustrative fixtures do **not** constitute actual NSE data or profit evidence.

Required for further acceptance: licensed historical and live NSE PE bid/ask source with first-known timestamps; verified exchange session/expiry/lot calendar; Indian transaction costs and broker margin, settlement and realistic two-leg fillability; no-lookahead out-of-sample and walk-forward evaluation; account-specific PAPER ledger, Flutter integration, and independent LIVE options permission/consent checks.

See [STR-184](STR-184-bear-put-debit-spread.md) and [canonical F&O feed](../architecture/fno-canonical-strategy-feed.md).


## Actual direct research adapter — 21 November 2026

`FnoCreditSpreadResearchService` now calls the **distinct**
`FnoBullPutCreditSpreadV1.evaluate` with source-first-known completed 35+
NIFTY close observations, the genuine same-expiry/lot **short higher strike
PE** and **long lower strike PE**, and synchronized verified index/option
bid/ask, independent OI/depth-in-lots for both option legs.

V108 contains **EMPTY** immutable broker-account-scoped market and margin
evidence. A verified provider-specific original quote is insufficient by
itself: the attested broker margin, current reconciled existing positions,
stress variation margin, potential settlement/assignment buffer, independently
reviewed stressed maximum loss, fees and risk budget must also exist.
Six separately verified model-inference rights receipts are required for
the index tick, both PE quote streams, instrument master, combined fees/budget
and account-specific margin evidence. A retrieved quote does not establish
order placement rights or prove a broker balance.

All keys and permissions default to unconfigured/false, including
`minttick.fno-research.credit-spread-research-rights-verified`,
`credit-spread-account-scope`, `credit-bull-lower-pe-key`, and
`credit-bull-upper-pe-key`. Missing actual source evidence, rights,
contract identity or funded margin blocks research. The modeled expiry
max loss and credit are **not realized P&L or a fill**. No real-data source
E2E, PAPER execution or LIVE orders are validated.

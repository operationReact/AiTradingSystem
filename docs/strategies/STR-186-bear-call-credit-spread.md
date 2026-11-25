# STR-186 — Bear Call Credit Spread (research-only)

**Catalog:** `strategy.STR-186` (Strategy 17/43). **Status: DATA_BLOCKED.**

This bearish **call credit vertical** sells one **lower-strike CE** (the risk-creating short leg) and buys one **higher-strike CE** (the protective long leg), on the same verified NIFTY underlying, exchange expiry and contract lot size. It requires a **new completed daily bearish EMA8-below-EMA21 crossover**, not simply an ongoing trend.

## Hypothetical two-leg expiry model

- Sell the lower CE at its source-verified bid less assumed sell slippage; buy the higher CE at its ask plus buy slippage.
- `creditPerUnit = lowerCallSellBidAfterSlippage − higherCallBuyAskAfterSlippage`, strictly positive and below `higherStrike − lowerStrike`.
- `maxModelGainPerLot = creditPerUnit × lotSize − modeledCombinedFees`
- `maxModelLossPerLot = ((higherStrike − lowerStrike) − creditPerUnit) × lotSize + modeledCombinedFees`
- `breakevenAtExpiry = lowerStrike + creditPerUnit − modeledCombinedFees / lotSize`
- `modelMaxRewardRiskBps = 10000 × maxModelGainPerLot / maxModelLossPerLot`

Model gain must exceed the explicitly configured research hurdle; modeled full max loss (not merely premium credit) must remain within the research capital budget. Combined fees include estimated statutory transaction costs and both legs; zero fees are rejected. The theoretical limited loss presumes both legs are filled and retained together. **No single-leg broker order or margin entitlement** is inferred from this calculation; naked short-call exposure is especially dangerous before the protective leg is actually established.

## Rights, data and time boundaries

- Both independent **source-listed CE option contracts** must have positive strike/tick, different ordered strikes, matching source-verified underlying, expiry and lot, listing date in range, and illustrative 3–45 calendar days to expiry.
- Require 35–512 strictly ascending independently verified completed NIFTY 15:30 IST daily closes, verified original publication time, single source provider, no future knowledge; source adapter separately attests NSE holidays and special sessions.
- Require independently rights-permitted index, option-chain and instrument-discovery research evidence. Real option bid/ask, positive **both-side quote depth**, OI in correctly normalized lot units, bounded strike distance, no crossed markets and explicit provider identity.
- Three exchange-time-aligned source snapshots (NIFTY and two CEs): each at most 180 seconds old, combined timestamp skew <=90 seconds, during NSE session, and originally observed no later than evaluation time. The final completed daily candle's original publication must predate all three quote exchange events. Missing data remains `BLOCKED_DATA`, not a synthetic substitute.

Outcomes: `BLOCKED_RIGHTS`, `BLOCKED_DATA`, `NO_SIGNAL`, `RESEARCH_CANDIDATE`. All remain **non-executable** (`executable=false`, `orderPlaced=false`, `liveTradeAuthorized=false`).

## Tests and acceptance blockers

11 JUnit methods cover bearish signal, bullish/flat no-signal, rights, contracts and expiry/lot/strike order, quote depth/OI/crossed market, provider continuity and timestamp skew, completed-candle as-of lookahead, net credit economics, nonzero fees/capital, after-cost reward/risk, and deterministic non-execution. Fixture prices are illustrative and have no claim of being genuine NSE quotes.

To become an eligible trading strategy requires licensed source permissions and genuine two-CE historical/live quotes, original timestamps, actual NSE strike/expiry/holiday/lot attestations, Indian statutory fees/broker margin and settlement evidence, non-atomic execution and fill-risk model, independent no-lookahead OOS/walk-forward tests, separate account-approved PAPER acceptance and LIVE risk checks. No production deployment, source activation, SSM, trading flag, Flutter screen or order API has been touched.

See [STR-185](STR-185-bull-put-credit-spread.md) and [canonical feed design](../architecture/fno-canonical-strategy-feed.md).


## Actual direct research adapter — 21 November 2026

`FnoCreditSpreadResearchService` also calls the **distinct**
`FnoBearCallCreditSpreadV1.evaluate` with a separately verified
same-provider three-leg NIFTY observation: **short lower strike CE** at
bid and **long higher strike CE** at ask, matched expiry, lot and
original as-of publication. This includes authenticated source-rights
records for completed NIFTY closes and both option contract sides,
depth/OI, instrument master and after-tax estimated fees/slippage.

Unlike premium-limited debit spreads, short CE risk needs real
account-scoped initial broker margin, stressed variation reserve,
existing-position reconciliation, settlement buffer and a reviewed
stressed-loss upper bound. V108 adds an **empty** append-only custody
table containing those distinct checks. No funds, rights or actual
options prices are defaulted.

`minttick.fno-research.credit-bear-lower-ce-key` and
`credit-bear-upper-ce-key` start empty; the credit rights and account
scope flags also fail closed. No source ingestion, broker execution,
paper order or live permission is supplied by test fixture candidates.
Theoretical bounded expiry outcomes are research-only, and real data
E2E/PAPER/LIVE all remain 0/43.

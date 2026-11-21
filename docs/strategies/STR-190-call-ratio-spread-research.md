# STR-190 — Call Ratio Spread Research (21/43)

**State: DATA_BLOCKED.** This is a deterministic *non-executable research model*, not an order permission.

## Structure and tail risk

A **1×2 call ratio spread** buys **one lower-strike NIFTY CE lot** and sells **two higher-strike CE lots** of matching true expiry, tick, contract lot and underlying, using independently licensed genuine option quotes. The second short call is *uncovered*. Therefore at expiry, underlying price increasing without bound can produce **theoretically unlimited loss**. There is **no finite maximum theoretical loss**, and no single stress scenario or user-selected capital amount makes that exposure defined-risk.

This structure is *not* a bull call debit spread: that uses one higher short call, not two. This strategy also does not hold underlying NIFTY to cover the extra short call.

## Deterministic model

Requires a **new bullish EMA8-over-EMA21 crossover** based solely on at least 35 original-observation, completed NIFTY daily 15:30 IST candles. The bullish crossover is a *research trigger*, not justification to sell an uncovered call.

- `longCostPerUnit = lowerCE.ask × (1 + buySlippageBps / 10000)`
- `twoShortReceivedPerUnit = 2 × higherCE.bid × (1 − sellSlippageBps / 10000)`
- `netDebitPerUnit = longCostPerUnit − twoShortReceivedPerUnit` (may be debit or credit)
- `netCostLot = netDebitPerUnit × optionLotSize + three-leg modeled all-in fees`
- `expiryPnl(S) = lotSize × (max(S − longStrike,0) − 2 × max(S − shortStrike,0)) − netCostLot`
- `theoreticalPeakPnl = expiryPnl(shortStrike)`; `upsideStressPnl = expiryPnl(explicitStressUnderlyingPrice)`
- `scenarioStressLoss = max(−upsideStressPnl,0)`

The research gate requires peak return greater than zero, an **explicit stress price beyond the two-short-call strike**, a positive scenario stress loss below an illustrative research scenario budget, and a minimum scenario peak-reward-to-stress-loss ratio. **This is NOT an enforced maximum loss, margin cap or safe sizing formula.** In a large upside gap, losses exceed the modeled stress arbitrarily. An uncovered F&O short leg can require substantially more margin than net premiums and carries severe non-atomic order risk.

Even if these research tests pass, all `Decision` objects retain `unlimitedTheoreticalUpsideLoss=true`, `executable=false`, `orderPlaced=false`, and `liveTradeAuthorized=false`. Market/paper/live order-routing stays unchanged.

## Data provenance and block rules

- Three independently licensed research rights: index, option chain and instrument discovery.
- Verifiable original listed NIFTY options, lower/higher strike **CE** identity, same expiry/lot, listing validity and positive strike/tick, 3–45 days to expiry (illustrative). Upper short CE requires **bid depth for at least two lots** and both call contracts require valid OI/depth.
- 35–512 verified completed daily NIFTY closes, explicit original exchange close and first-known publication; no unpublished/future bars, duplicated sessions, or cross-provider switching. A genuine NSE exchange calendar and special sessions must be independently verified by the provider adapter.
- Same-provider synchronized index and both call bid/ask snapshots, both sides positive and noncrossed, cross-leg skew <=90 seconds, age <=180 seconds, all originally observed by `asOf`, last daily candle already known before each quote event. The 09:15–15:30 IST market window and first-known constraints are not permission to fabricate data.
- True all-in modeled three-leg fees and per-leg slippage. Missing true market depth, rights, contract mapping, timestamp metadata or official lots means `BLOCKED_DATA` or `BLOCKED_RIGHTS`.

## Test coverage and outstanding acceptance

12 deterministic JUnit cases validate 1×2 ratio, maximum *peak* value vs upside stress loss, explicit unbounded loss and non-executable fields, new completed bullish trigger, independent rights, invalid CE contract/expiry/lot, insufficient two-short-leg depth, crossed/cross-provider quotes, stale/future quote or candle publication, scenario budget failure, stress not above short strike, fee correctness and deterministic behavior. **Synthetic unit-test prices are not market feed records.**

**Open blockers:** authenticated/rights-permitted genuine NIFTY and NSE_FO option source, complete original exchange as-of timestamps, actual spread liquidity for two short contracts, margin/stress testing far beyond one scenario, broker uncovered-writing permissions and operational safety, non-atomic multi-leg execution proof, robust Indian transaction taxes/settlement details, independent OOS walk-forward and fill replay, separately approved PAPER and LIVE acceptance. Since the default product goal favors controlled drawdowns, this uncovered-tail-risk strategy must remain research-only unless a different structure makes risk genuinely bounded and permissioned.

See [STR-189 Collar](STR-189-equity-collar.md).


## Direct read-only source adapter — 21 November 2026

`FnoRatioSpreadResearchService` directly invokes
`FnoCallRatioSpreadV1.evaluate` for exactly one source-listed long
lower-strike NIFTY CE and **two** higher-strike short NIFTY CE lots. The
new V111 source table starts **EMPTY** and requires actual same-provider,
same-expiry, source-normalized lot identity, original-time synchronized
index + two option BID/ASK quotes and short bid depth >= two full
contracts. Broker-account-scoped margin reserves, explicitly
non-duplicated Indian three-leg fees and independently reviewed
stress assumptions must be evidenced; V106 source-first-known 35+
completed NIFTY closes are separately mandatory.

Five independently verified currently valid
`MODEL_INFERENCE` research-use rights are required for index tick,
long and short option chain depth/OI, contract master and 1x2
ratio-specific margin/fee source. `ratio-research-rights-verified`
defaults **false** and `ratio-account-scope` and both CE keys default
blank. No bridge from a two-leg debit spread's fee estimate is allowed.

**Unbounded upside loss** remains true for every call ratio spread.
No finite stress scenario, broker margin snapshot or research
candidate changes that mathematical risk. This path is **never
executable**, cannot place paper or real orders, and does not imply
real-source data completion or profitability.

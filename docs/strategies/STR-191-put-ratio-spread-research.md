# STR-191 — Put Ratio Spread Research (Strategy 22/43)

**State:** `DATA_BLOCKED`. Deterministic, **research-only**, never broker-executable.

## Strategy definition and major risk

The 1×2 **put ratio spread** buys **one higher-strike NIFTY PE lot** and sells **two lower-strike NIFTY PE lots** with exactly matching verified NSE-listed underlying, contract lot and expiry. The second short put is **uncovered**. In a crash, this can incur substantial downside losses, margin calls and forced liquidation.

**Contrast with STR-190 Call Ratio Spread:** a stock index is nonnegative. Consequently, the payoff's *theoretical expiry loss* for this put ratio is finite—**not unlimited** as in the additional uncovered call. The true worst expiry value must be evaluated at both `S=0` and above the long put strike, not approximated using a single chosen stress scenario. The finite expiry bound **does not** imply bounded broker margin, safe exits, guaranteed fills, or permission to write uncovered options.

## Formulas

One genuinely listed lot of `Q` options, higher long PE strike `H`, lower short PE strike `L`, `H > L > 0`, assumed slippage and all-in three-leg estimated fees:

- `longCost = longPE.ask × (1 + buySlippageBps/10000)`
- `twoShortPremium = 2 × shortPE.bid × (1 − sellSlippageBps/10000)`
- `netCost = Q × (longCost − twoShortPremium) + modeled fees`
- `expiryPnl(S) = Q × (max(H−S,0) − 2×max(L−S,0)) − netCost`
- `maximumPnl = expiryPnl(L)` (peak at the lower short-put strike)
- `trueWorstExpiryPnl = min(expiryPnl(0), −netCost)` (S at or above H)
- `theoreticalMaxExpiryLoss = max(0, −trueWorstExpiryPnl)`
- `stressPnl = expiryPnl(explicit downside stress S)`, requiring `0 < S < L`

All of the modeled *true theoretical maximum expiry loss* (not just selected stress loss) must fit the configured research risk budget. Peak reward must exceed the research reward/maximum-loss threshold. This limit is a **research filter, not evidence of broker margin or account solvency**. At all times `Decision.unlimitedTheoreticalDownsideLoss = false` and `executable = orderPlaced = liveTradeAuthorized = false`.

## Input rights, data, and as-of gates

- Licensed index/option-chain/instrument-discovery research rights; genuine original market data, *no* invented or synthetic production quotes.
- The research signal requires **new bearish EMA8/EMA21 crossover** in at least 35 genuinely verified, ascending **completed** NIFTY daily 15:30 IST exchange closes with original observation timestamp not later than as-of. Trend direction without a fresh crossover is insufficient.
- Source-listed same-expiry, same-lot verified PE contracts (one higher strike, two lower-strike shorts), original listing date and positive tick/strike. Two short contracts need real executable bid depth for **both** lots, independent of the long ask depth.
- Three synchronized same-provider market snapshots (NIFTY spot, long PE, short PE), each <=180 seconds old, <=90 seconds total exchange-time skew, positive and noncrossed bid/ask, real depth/OI; original first-known timestamps cannot be future-known. Actual holidays and NSE special sessions require separately verified upstream calendar.
- Explicit all-in modeled India transaction, stamp, brokerage, STT and expiry/settlement charges. Missing data yields `BLOCKED_RIGHTS` or `BLOCKED_DATA`.

## Tests and conditions remaining

12 deterministic JUnit cases cover a peak-positive bearish candidate, both actual matching PE legs, two-short-lot quote depth, missing rights, malformed/illiquid or cross-provider market, future-known candles or quotes, absent bearish crossover, zero fees, worst-case zero-index loss budget, invalid stress price and deterministic nonexecution. Test fixtures are **hypothetical and not market observations**.

Outstanding: licensed first-known index/F&O quotes, official exchange expiry and lot master, actual short-put margin permissions, Indian settlement/physical/cash rules, original as-of out-of-sample and walk-forward profitability evidence, actual multi-leg fill and partial-fill safety, full PAPER acceptance and independent LIVE authorization. No app feature flags, credentials, brokers, routes, deployments or APKs change with this PR.

See [STR-190 Call Ratio Spread](STR-190-call-ratio-spread-research.md).


## Direct read-only source adapter — 21 November 2026

`FnoRatioSpreadResearchService` invokes the distinct
`FnoPutRatioSpreadV1.evaluate`: one genuine higher-strike
long NIFTY PE and **two** lower-strike shorts with same expiry/lot
and source-verified underlying/chain quotes. Requires broker-account
margin reconciliation and two-lot short-side BID depth, original
35+ first-known completed NIFTY closes, complete modeled three-leg
tax/slippage, current instrument rights and researched capital.

The maximum expiry put loss is mathematically finite because
the underlying cannot fall below zero, but may still be severe;
a finite payoff bound **does not** prove sufficient margin,
liquidation constraints, settlement funding or executable orders.
V111 is empty and all research flags/keys default denied/unconfigured.
No independent real-market E2E, PAPER or LIVE proof is claimed.

# STR-212 — Skew-Based Hedge Research (Strategy 43/43)

**Catalog: `DATA_BLOCKED`; NON-EXECUTABLE.** This is the last item in the **43-strategy research catalog**, *not* the completion of licensed data, a profitable backtest, active broker order execution or trading readiness.

## Exact hypothesis and why skew is not itself an edge

For a BROKER-CONFIRMED **existing LONG NIFTY future**, consider a hypothetical protective put **debit spread**: **BUY higher-strike OTM NIFTY PE, SELL lower-strike NIFTY PE**, exact same real NIFTY underlying, authentic expiry and official lot size as the already-held future. Compute source-original **put-versus-call implied volatility skew** from the bought put's original IV and that of a **separately listed OTM NIFTY CE with comparable absolute delta** (both same expiry). The reference CE is **never sold or purchased**.

`sourceOriginalPutCallIvSkewVolPoints = higherBoughtPutOriginalIvPercent - comparableReferenceCallOriginalIvPercent`.

Require positive threshold and `abs(abs(originalPutDelta) - originalCallDelta) <= maxAbsDeltaMismatch`. Percent IV must be expressed in **annualized percentage points**, not fractional volatility nor basis points of option price. A steep put skew merely indicates that puts are priced at higher implied volatility; this may make outright puts *expensive*. A sold lower put can help finance a purchased higher put but eliminates **further protection below the lower put strike**. The strategy never claims that selling skew is a free edge, insurance, unlimited protection, or better than the outright put. One theoretical spread is paired to each **verified positive long future lot**.

## Original-source and rights gates

Six separately attested genuine research rights: (1) reconciled broker existing LONG NIFTY FUTURES position and historical original actual entry price, (2) genuine originally completed NIFTY daily index history, (3) permitted authentic NSE listed NIFTY option bid/ask chain, (4) source-original option put/call delta and annualized IV, (5) real listed NIFTY futures bid/ask, and (6) official independent listed NSE options/futures instrument master with genuine expiry/lot/ticks. All inputs with `sourceVerified`/`brokerVerified` represent necessary **caller attestations**, not evidence that an actual feed, broker entitlement or write API is working.

- Authenticated broker position is source-original known **before** the current exchange snapshot, has real **positive long futures lots**, correct source-listed NIFTY FUT key, original broker futures entry basis and original official lot size.
- All three option contracts are distinct, originally listed genuine `NSE_FO|` contracts; `lowerPutStrike < higherPutStrike < currentNiftySpot < referenceCallStrike`, put spread width at least policy minimum, identical official lot, identical actual expiry with existing NIFTY future, real listing dates and tick-aligned strikes. Do not invent an NSE weekly-expiry weekday.
- Fresh genuine original NIFTY index spot, futures BID/ASK, **all three** option original BID/ASK, original signed delta, annualized IV, OI, original quote exchange and first-known time, strictly same source provider and synchronized exchange snapshot. True **bought-put ASK depth** and **sold-lower-put BID depth** must cover **all verified existing futures lots**. No synthetic Greek, LTP substitute, invented depth or mixed feed/source. Bid/ask width, stale or future-known records fail closed. The OTM CE is an **original delta-matched skew reference only**.
- At least **35 genuinely completed first-known NIFTY index closes**, exact exchange-close timestamps, matched original provider, strictly ascending sessions, no duplicate or future-known rows. Model the observed largest negative completed NIFTY daily return. Historical tail moves do not predict future movements or tell us if IV quotes were attractive in the past. Source-original real-option closing/expiry quote history and actual holiday sessions are **still missing**.

## Conditional common-expiry payoff, not realized performance

For `Q=brokerVerifiedLongFutureLots×officialFutureLot`, actual broker-reported `F_entry` and source quote ASK/BID and modeled India costs:

`outrightLongPutDebit = Q×higherPE_ASK_after_buy_slippage + positiveOneLegFee`.

`putSpreadDebit = Q×(higherPE_ASK_after_buy_slippage − lowerPE_BID_after_sell_slippage) + positiveTwoLegFee`.

`premiumSavings = outrightLongPutDebit − putSpreadDebit`; must be strictly positive, modeled `putSpreadDebit` must be strictly positive, less than both quoted expiry wing protection and the configured debit budget. Any savings are *hypothetical and contingent on executable fills*, not actual profit or evidence of broker margin sufficiency.

`maxPutSpreadIntrinsicProtection = Q×(higherPutStrike−lowerPutStrike)` (only a **cap on option intrinsic spread payoff at expiry**, NOT a maximum-loss bound on the long future).

Replay the largest originally observed daily down return `r_worst` from actual completed closes as **hypothetical NIFTY common-expiry settlement** `S_stress=currentGenuineSpot×(1+r_worst)`. This assumes an observed *one-day* historical shock **persists until future expiry**, which cannot be established by this model. Compute:
- `unhedgedFutureStressPnl = Q×(S_stress − originalBrokerFutureEntry)`.
- `putSpreadFutureStressPnl = unhedgedFutureStressPnl + Q×[max(higherPEStrike−S_stress,0)−max(lowerPEStrike−S_stress,0)] − putSpreadDebit`.
- Only return a research candidate if the theoretical after-premium stress result improves on the unhedged future; a steep skew **alone cannot trigger candidate status**.
- At **index S=0**, `conditionalPutSpreadFutureAtZeroPnl = Q×(0−F_entry) + Q×(higherPEStrike−lowerPEStrike)−putSpreadDebit`.
- At **index S=0**, `conditionalOutrightPutFutureAtZeroPnl = Q×(0−F_entry) + Q×higherPEStrike−outrightLongPutDebit`.

The zero-index comparison is central: the debit spread remains exposed to the entire remaining NIFTY futures decline **below** the sold lower-strike put, whereas the outright purchased higher-strike PE retains more tail intrinsic payoff. There can be enormous cash/margin/futures-variation losses long before expiry. A short lower put has separate real broker margin and fill/legging/assignment or settlement obligations; these are NOT simulated. No netting, broker permissions, exercise, actual hedge effectiveness, after-cost OOS returns, or rolling margins are proven. The synthetic **JUnit fixtures are not actual NSE market data**, and no source rights are established by these tests.

## Evidence, rejection and activation gates

- Evaluator: `backend/src/main/java/in/aitradingsystem/tradingrooms/FnoSkewBasedHedgeV1.java`
- Tests: `backend/src/test/java/in/aitradingsystem/tradingrooms/FnoSkewBasedHedgeV1Test.java`
- Capability manifest: `backend/src/main/resources/mind/capability-manifest.v1.json`, `strategy.STR-212`
- **22 fictional deterministic JUnit cases** verifying IV skew/delta comparability, exact genuine instrument official lot/expiry/tick, broker positive long position, 35+ original completed source sessions and provider consistency, correct quoted BUY ASK and SELL BID depth and fees, premium savings, shallow-wing net-stress rejection, source data/rights fail-closed, explicit zero-index extreme-tail short put exposure and no-order invariants.

All decisions unconditionally `executable=false`, `orderPlaced=false`, `paperTradeAuthorized=false`, `liveTradeAuthorized=false`. Manifest stays `DATA_BLOCKED`. Real PAPER activation needs licensed authentic NSE original-time index/futures/options data with original IV/skew/real depths and holiday/instrument provenance, source-reconciled broker position/ledger, official margin/fees/settlement and multi-leg partial-fill/rollback, after-cost walk-forward empirically verified return and tail scenarios, safety guards, independent paper portfolio ledger and Flutter acceptance. LIVE separately needs user-approved real F&O broker write entitlements, current risk/kill switches and funding/collateral verification. **This PR makes NO AWS/SSM, broker flag, order, Flutter deployment, or APK change.**

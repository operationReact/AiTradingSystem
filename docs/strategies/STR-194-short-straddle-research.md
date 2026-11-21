# STR-194 — Short Straddle Research (Strategy 25/43)

**State: DATA_BLOCKED.** This is **non-executable** F&O research. A short straddle has an **unbounded theoretical loss on the upside**, which is incompatible with treating a finite stress scenario, collected premium or nominal account capital as maximum loss.

## Actual strategy structure and payoff

Sell **one source-listed NIFTY CE and one PE** with the exact **same strike K, expiry, underlying and lot size Q**. A filled short CE and PE can receive premium but require substantial uncovered F&O writing margin, monitoring and settlement handling. A hypothetical research signal is **not** evidence that broker margin, permissions, fill safety or actual option prices are available.

For independently verified executable market bids, explicit slippage and combined modeled Indian fees:

- `netCredit = Q × [CEbid × (1−sellSlippageBps/10000) + PEbid × (1−sellSlippageBps/10000)] − allInFees`
- `expiryPnl(S) = netCredit − Q × [max(S−K,0) + max(K−S,0)] = netCredit−Q×abs(S−K)`
- `theoreticalPeakProfit = netCredit` when `S=K` at expiry. This is an *upper profit bound*, **not** expected performance.
- `lowerExpiryBreakeven = K−netCredit/Q`; `upperExpiryBreakeven = K+netCredit/Q`
- `theoreticalMaxDownsideExpiryLoss = max(0, Q×K−netCredit)` at `S=0`, a potentially huge loss.
- **Theoretical maximum upside loss is unlimited** as `S→∞` because the uncovered CE short intrinsic grows without limit. `unlimitedTheoreticalUpsideLoss=true` on **every** returned decision, even blocked and no-signal states.

An explicit capital budget caps suspicious collected credit for the *research classifier*; it is **not a margin requirement or a safety cap**, since losses are not bounded by credit. Strict **no-order** fields are `executable=false`, `orderPlaced=false`, `liveTradeAuthorized=false` on every output.

## IV and timestamp validation

- The input must have independent rights to index, option chain, instruments and separately licensed annualized implied-volatility percentages for both CE and PE. Never fabricate IV from a last-traded price or a generic index volatility number.
- At least 35 authenticated source-completed NIFTY closes at 15:30 IST, consistent provider, original first-seen timestamp, no forward bars or duplicate dates. Official special NSE sessions/holidays must be independently validated upstream.
- Two genuine same-strike/same-lot/same-expiry CE/PE contracts; true source-listed strike, tick and listing dates, illustrative 3–45 calendar DTE.
- Actual synchronized NIFTY spot, CE and PE broker quotes: both positive noncrossed bid/ask, genuine **bid-side** depth and OI for each sold leg, original exchange and first-known timestamps aged <=180 seconds and cross-leg skew <=90 seconds.
- Real modeled sell-side slippage, combined positive Indian transaction charges and fees. Refuse negative or zero after-cost net premium.

A transparent, *illustrative only* one-sigma IV move uses `spot × mean(CEiv,PEiv)/100 × sqrt(calendarDTE/365)`; the model compares that move to net premium per unit, rejecting research candidates when the move exceeds the selected threshold. **This is not a quantile or a probabilistic risk bound**; extreme index gaps can far exceed IV estimates.

## Validation and non-trading blockers

11 deterministic Java/JUnit tests cover matching CE/PE candidate, source rights, wrong strike/type/lot/expiry, invalid IV, crossed/illiquid/cross-provider/stale/future quotes, completed history integrity, suspicious premium sanity cap, high IV implied move, nonpositive after-cost credit, positive fee requirement and non-execution.

Real exchange/broker source data, original IV provenance, actual uncovered-writing permissions and margin, Indian exchange settlement, realistic non-atomic fills, full historical as-of out-of-sample and walk-forward evaluation, a risk policy that permits—or expressly excludes—unbounded-loss strategies, separately approved PAPER and LIVE acceptance are **still absent**. None of the research test fixture values are actual NSE quotes.

Related: [STR-192 Long Straddle](STR-192-long-straddle-research.md) and [STR-193 Long Strangle](STR-193-long-strangle-research.md).

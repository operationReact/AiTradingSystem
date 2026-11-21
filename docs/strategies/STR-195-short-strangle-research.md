# STR-195 — Short Strangle Research (Strategy 26/43)

**Status: DATA_BLOCKED.** This is a deterministic **non-executable research evaluator**, not live or paper trading activation.

## Strategy and extreme risk

Sell one genuine source-listed, **out-of-the-money higher-strike NIFTY CE** and one genuine source-listed, **out-of-the-money lower-strike NIFTY PE**, both with the same expiry and lot size. Both option legs are uncovered. `PEstrike < current index spot < CEstrike` is mandatory; without actual contemporaneous underlying quotes this test is blocked. Do not confuse this two-strike strangle with STR-194 Short Straddle (same strike).

The short CE creates **theoretically unlimited upside losses**. The short PE creates a **severe but finite theoretical downside loss** at index value zero. Collected credit, observed IV, simulated stress loss, and nominal account equity are **not** risk caps. Actual broker margin and forced liquidation risk may exceed initial credit.

Let `P` = lower PE strike, `C` = higher CE strike, `Q` = option lot, `R` = after-slippage and after-all-in-fees total collected credit:

- `R = Q × [CEbid × (1 - sellSlippageBps/10000) + PEbid × (1 - sellSlippageBps/10000)] - modeledFees`
- `expiryPnl(S) = R - Q × [max(S-C,0) + max(P-S,0)]`
- `maximumTheoreticalExpiryProfit = R` for `P ≤ S ≤ C`, not a profitability promise.
- `lowerExpiryBreakeven = P - R/Q`; `upperExpiryBreakeven = C + R/Q`.
- `maximumTheoreticalDownsideExpiryLoss = max(0, Q×P-R)` at `S=0`.
- `maximumTheoreticalUpsideLoss = UNLIMITED` as underlying spot increases without bound. Every returned `Decision` includes `unlimitedTheoreticalUpsideLoss=true`.

A nonpositive after-fee credit is rejected. A sanity threshold on collected credit versus a research capital figure is **not a trading position size or margin limit**. The source-IV one-sigma move heuristic `indexSpot × mean(CEiv,PEiv)/100 × sqrt(calendarDaysToExpiry/365)` is compared with received credit per unit to reject some unrealistic volatility/credit combinations. **No probability, risk bound or out-of-sample edge is established** by this heuristic.

## Point-in-time and source conditions

- Licensed rights to separately use genuine NIFTY index, listed option chain, instrument discovery and **annualized source IV percentage for each option**. Never invent an IV input.
- At least 35 genuine completed daily NIFTY 15:30 IST source observations in ascending order, original source-first-known timestamps and same-provider continuity. Actual NSE exchange holidays/special sessions require independently verified upstream market calendar.
- Two independently source-verified NSE_FO option instruments: **CE strike strictly above PE strike**, equal lot/expiry, valid listing date and source tick, illustrative 3–45 days to expiry.
- Same-provider synchronized true NIFTY, CE and PE quotes (<=180-second age; <=90-second cross-leg skew), positive noncrossed bid/ask and genuine **bid-side depth** and OI on both sold legs, originally available before `asOf`; observed spot must lie strictly between the strikes.
- Nonzero modeled brokerage, STT, GST, exchange fees, stamp and other applicable charges. Missing rights, feed, instrument identity, IV or timestamp provenance fails closed.

All states (`BLOCKED_RIGHTS`, `BLOCKED_DATA`, `NO_SIGNAL`, `RESEARCH_CANDIDATE`) return `executable=false`, `orderPlaced=false`, `liveTradeAuthorized=false`. No order API is invoked.

## Validation and unresolved acceptance

12 deterministic JUnit scenarios cover an OTM candidate, independent rights, contract kind/expiry/lot/strike, two distinct OTM spot boundaries, invalid IV, crossed/thin/cross-provider/stale/future quotes, completed history as-of, implausible net premium, oversized implied move, nonpositive credit, zero fees and absolute non-execution. **Unit-test prices are intentionally fictional fixtures**, not a broker market feed.

**Unresolved:** true licensed option-chain + annualized IV inputs, original first-observation timestamps, NSE contract/expiry/lot master, India short-option margin and settlement requirements, gap/volatility and partial-fill failure modeling, genuine no-lookahead OOS/walk-forward evidence, independent PAPER ledger/Flutter acceptance, explicit LIVE broker permissions and audited kill switches. Unbounded-risk structures should not be automatically traded for capital-preservation objectives.

Related: [STR-194 Short Straddle Research](STR-194-short-straddle-research.md).

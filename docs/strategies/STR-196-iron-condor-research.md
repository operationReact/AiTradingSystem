# STR-196 — Iron Condor (Strategy 27/43)

**Status: DATA_BLOCKED.** Four-leg defined-risk expiry payoff research, **not an executable trade** or proof of broker margin acceptance.

## Four genuine NIFTY option instruments

Each lot is on the *same independently source-verified NIFTY underlying, actual expiry and identical official contract lot size*. Strike ordering is mandatory:

`long PE (A) < short PE (B) < short CE (C) < long CE (D)`

The short put and short call must surround the **true contemporaneous index spot**: `B < spot < C`. Buy one farther OTM protective PE (`A`), sell one nearer OTM PE (`B`), sell one nearer OTM CE (`C`), and buy one farther OTM protective CE (`D`). No duplicated instrument keys, invalid strike steps, mismatched expiries/lots, or future-unlisted contracts.

Both **long wings must remain intact** for the following limited-loss theoretical expiry payoff to hold. Partial fills, inability to exit, dynamic margin, option exercise, broker liquidation and operational failures can invalidate the assumed multi-leg protection.

## Exact payoff and true worst-width loss

For actual contemporaneous option bid/ask and an explicitly modeled **four-leg all-in Indian brokerage/exchange/tax fee**:

- `credit = lot × [shortPEbid×(1−sellSlippageBps/10000) + shortCEbid×(1−sellSlippageBps/10000) − longPEask×(1+buySlippageBps/10000) − longCEask×(1+buySlippageBps/10000)] − fees`.
- `expiryPnl(S) = credit − lot×[max(B−S,0)−max(A−S,0) + max(S−C,0)−max(S−D,0)]`.
- `theoreticalMaxExpiryProfit = credit` when `B ≤ S ≤ C` at expiry.
- `putWingWidth = B−A`, `callWingWidth = D−C`.
- **`theoreticalMaxExpiryLoss = max(0, lot × max(putWingWidth, callWingWidth) − credit)`.** Crucially, use the **wider of asymmetric wings**, not average width or the put side alone.
- `lowerBreakeven = B−credit/lot`, `upperBreakeven = C+credit/lot`.

Net credit must be positive and smaller than the gross maximum wing risk. A research limit compares **maximum theoretical expiry loss** to the configured fraction of research capital, and a reward/risk threshold is applied. **Neither is an actual broker margin limit nor a safe allocation**, and no separate position permissions are established here.

## IV, provenance and bounded scope

- Four independent research permissions: index quotes, option chain, instrument discovery, and **separately licensed source-derived annualized option IV** for **each** of the four legs. IV uses percentage units (e.g. 20 = 20%), never inferred from fabricated LTP.
- At least 35 verified completed, provider-consistent NIFTY daily observations with authentic 15:30 IST closes and original `firstKnownAt`; no future or duplicate session, holidays/special sessions must be verified upstream.
- Synchronize **five** actual same-provider exchange snapshots (NIFTY spot and four option bid/asks) within 90 seconds total skew, no quote older than 180 seconds. Preserve original first-seen timestamps, valid bid/ask and OI. Validate **bid depth on BOTH short options**, **ask depth on BOTH protective long options**, and available IV for each option.
- An illustrative one-sigma IV move uses `spot×average(four leg IV percentages)/100×sqrt(calendarDTE/365)`. A filter compares this heuristic with the distance to the closer inner short strike. It is **not a probability, tail bound, expected profit, or backtest**. No portfolio positions, forecast surfaces or broker authorization are fabricated.
- All decisions including `RESEARCH_CANDIDATE` return `executable=false`, `orderPlaced=false`, `liveTradeAuthorized=false`. The source is a standalone deterministic Java evaluator, without any order submission.

## Validation and unresolved blockers

12 JUnit tests cover candidate source-verified 4-leg structure, source rights, unique identities and expiry/lot/strike order, index between shorts, invalid IV, wrong-provider/crossed/illiquid quotes and direction-specific depth, stale/future/unsynchronized data, duplicate/future-known completed bars, **asymmetric-width worst expiry risk**, after-fee negative credit, actual maximum-loss allocation, high IV move, zero fees, and deterministic nonexecution. Unit-test values are *not* live NIFTY quotes or backtest results.

Before any PAPER/LIVE usage: true licensed option-chain + original as-of annualized IV, actual NSE master and exchange sessions, Indian fee and settlement/strike rule checks, broker margin and all-leg atomicity/legging/hedge safety, original-timestamp out-of-sample walk-forward testing including fills and overnight gap stress, distinct PAPER ledger acceptance and explicit LIVE broker permission/risk kill switches. This classifier remains **DATA_BLOCKED** without those independent proofs.

Related: [STR-195 Short Strangle Research](STR-195-short-strangle-research.md).

# STR-198 — Long Butterfly (Strategy 29/43)

**Status: DATA_BLOCKED.** A **non-executable**, deterministic **long CALL butterfly** F&O research evaluator. It does not create broker orders or prove returns.

## Correct structure and 1 : −2 : 1 position quantities

Buy **one** independently source-verified lower-strike NIFTY CE at A; sell **two** identical center-strike NIFTY CE contracts at K; buy **one** upper-strike NIFTY CE at D. All positions must have the exact **same official NSE option lot and expiry** and originate from genuine listed instruments in a licensed source feed. This evaluator is **not** STR-197 Iron Butterfly, which sells a central **CE and PE**; here **all three strike levels are calls (CE)**, with the middle leg sold at twice the other leg quantity.

Require **A < K < D** and **K−A = D−K** (a conventional *symmetric* long butterfly). Broken-wing variations are a different strategy and are rejected. The true source NIFTY spot must be near K, and spot must remain between A and D for this research configuration.

## Payoff, fees and theoretical risk

With genuine current call bid/asks, illustrative buy/sell slippage, full **four-option-contract** Indian transaction fees and exact matching lot Q:

- `debit = Q × [(lowerAsk + upperAsk) × (1 + buySlippageBps/10000) − 2 × centerBid × (1 − sellSlippageBps/10000)] + allInFees`
- `expiryPnl(S) = Q × [max(S−A,0) − 2×max(S−K,0) + max(S−D,0)] − debit`
- `theoreticalMaximumExpiryLoss = debit` (for `S ≤ A` or `S ≥ D`, assuming all three contracts in the **1:−2:1 ratio** remain intact)
- `theoreticalMaximumExpiryProfit = Q × (K−A) − debit` (at center `S=K`)
- `lowerExpiryBreakeven = A + debit/Q`
- `upperExpiryBreakeven = D − debit/Q`.

Net debit must be positive, strictly below the gross triangular peak, and within an explicitly modeled **research-only** premium-at-risk allocation. The calculated maximum theoretical loss is **not an actual executable margin, partial-fill or early-exercise guarantee**. If the protective long legs are missing, the two short center calls create substantial naked exposure.

## Source, IV, as-of and liquidity requirements

- Four independent research permissions: genuine source index quotes, listed options, instrument discovery and **source-reported annualized CE implied-volatility percentages**. Do not derive IV from synthetic price or a different market source.
- 35–512 verified, completed NIFTY daily 15:30 IST observations with original `exchangeCloseAt` and `firstKnownAt`, strictly increasing valid sessions, one consistent data provider, and no future knowledge. The official NSE calendar and special sessions require separate upstream verification.
- Four synchronized original-quote observations: current NIFTY index and lower, middle, upper listed calls. Genuine source `bid`, `ask`, exchange/first-known timestamps (no older than 180 seconds and <=90 seconds cross-leg exchange-time skew), noncrossed quotes, spread and real open interest.
- **Buy-side ask depth** on BOTH long option instruments; **bid-side depth for at least TWO lots** at the central short option instrument. Distinct option keys, same official lot, listed-from date, exchange-aligned strike/tick and expiry.
- Weighted illustrative IV one-sigma movement, `spot × (lowerIV + 2×centerIV + upperIV)/4/100 × sqrt(calendarDTE/365)`, compared to strike-wing width as a volatility **heuristic**, never a forecast, probability or backtest.
- Reject missing, negative, stale, mismatched-provider or over-limit data and inadequate reward/risk. `RESEARCH_CANDIDATE` is a research classification only.

Every result, including blocked and candidate states, must return `executable=false`, `orderPlaced=false`, `liveTradeAuthorized=false`.

## Verification and genuine missing evidence

13 JUnit test cases cover 1:−2:1 after-cost payoff, independent source rights, exact same-strike-spacing/lots/expiry option identity, point-in-time history, ATM positioning, independently verifiable IV and real two-lot bid depth, old/future/cross-provider/crossed quotes, invalid debit or excessive debit, premium budget, reward/risk, high IV movement, nonzero fees and invariant no-order outputs. **Test quotes are illustrative fixtures and not actual NIFTY exchange data**.

**Still blocked:** authenticated licensed NIFTY CE chain, annualized IV and original historical as-of feed; true official NSE lot/expiry/calendar and fee schedule; liquid four-unit matched broker fills, margins, settlement and hedging/legging safeguards; out-of-sample walk-forward evidence with slippage and realistic post-cost performance; independent PAPER ledger/app acceptance and LIVE brokerage permissions/risk kill switches. This PR does not turn on any F&O execution path.

Related: [STR-197 Iron Butterfly](STR-197-iron-butterfly-research.md).

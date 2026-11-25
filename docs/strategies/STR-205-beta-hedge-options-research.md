# STR-205 — Beta Hedge with Options (36/43)

**Research status: `DATA_BLOCKED`.** This is a **pure, non-executable** point-in-time classification of a **hypothetical long NIFTY index option hedge** against the *observed* beta of verified long-only equity holdings. It is not a portfolio order, profitability result, insurance guarantee, backtest, or broker authorization.

## Scope and source requirements

The evaluator requires **six independently verified rights**: broker-confirmed equity holdings research, original NIFTY daily index history, original individual equity historical closes, genuine original provider option Greeks, the actual option chain, and the NSE option instrument master.

It requires:
- 65+ **source-verified, completed, exchange-time and original first-known daily closes** for the NIFTY index and **every held stock**, same provider, synchronized same session; no future-known or incomplete candles, crossed broker history, duplicate identities, or negative/missing closes. Policy minimum cannot drop below 61 sessions (60 daily returns); tests use 70 sessions.
- Currently **broker-confirmed positive long share quantities** and actual source quotes for each holding, a matched provider's index quote, authentic contract identities and nonstale first-known timestamps. It does not assume short equity shares, leveraged portfolios, options inventories or historical portfolio turnover.
- Genuine **NSE-listed NIFTY PE or CE** with valid listing/expiry/strike/official lot, contemporaneous original option **bid and ask**, source-reported **per-underlying-unit delta** and annualized IV, actual open interest, **ask-side depth**, source/provider rights, synchronized exchange and original first-known timestamps. Never invent implied-volatility series, Greeks, option liquidity or historic observations.

## Local first-order exposure estimation

Let `V_i = verifiedShares_i × currentVerifiedMarketPrice_i`, `w_i = V_i / SUM(V_i)`. On each pair of completed source-original prior sessions:

- `rIndex,t = IndexClose_t / IndexClose_(t-1) − 1`
- `rPortfolioProxy,t = SUM(w_i × [EquityClose_i,t/EquityClose_i,t-1 − 1])` using *today's* portfolio weights held **constant across the observed sessions**
- `observedBeta = Cov(rIndex, rPortfolioProxy) / Var(rIndex)` from 60+ original completed daily returns, after rejecting low index variance
- `observedBetaExposureInr = observedBeta × SUM(V_i)`
- `oneOptionLotDeltaExposureInr = genuineOptionDelta × officialOptionLot × authenticCurrentNiftySpot`

**Important:** the fixed-current-weight series is a synthetic *portfolio return proxy* built from authentic price observations, **not an actual historical portfolio return record**. Historical portfolio weights, fills, funding and cash flows are unknown. Historical beta can drift and is **not** a future beta guarantee.

For **positive** observed portfolio beta, only a **hypothetical BUY put** (negative genuine option delta) may reduce the local first-order equity-index exposure; for **negative** observed beta, only a **hypothetical BUY call** (positive genuine option delta) may do so. Small beta produces `NO_HEDGE`. No short options, straddles, futures, stocks, or order APIs.

Evaluate integer 1..max eligible option lots, bounded by **source-proven real ASK depth**. Choose the lot count with minimum absolute residual local beta exposure among strictly improving candidates. Demand minimum configured reduction, then model the full long-option ASK debit, modeled buy slippage, positive fees and a strictly enforced portfolio premium budget. The purchase-cost estimate is **not** a loss forecast or settlement-correct payoff; it does not include option exit values, early exercise, expiry settlement, implied-volatility skew changes, margin offsets, exact Indian statutory charges, actual fills or impact.

## Failure modes and acceptance gates

If any source right, broker holdings proof, matched quote, original first-known timestamp, complete price history, listing/official lot, provider consistency, delta sign, IV, OI or ASK liquidity fails verification, result is `BLOCKED_RIGHTS` or `BLOCKED_DATA`. Insufficient beta, direction mismatch, too little local exposure reduction or excessive hypothetical premium return `NO_HEDGE`. No fallback to generated Greeks, static beta assumptions or fake instruments.

**Every result, including `RESEARCH_CANDIDATE`, is unconditionally `executable=false`, `orderPlaced=false`, `paperTradeAuthorized=false`, `liveTradeAuthorized=false`.** The capability catalog remains `DATA_BLOCKED`.

To progress beyond research, separately acquire/licence real historical indexed equities and original as-of NIFTY chain/Greeks, reconcile actual broker holdings and official NSE lot/expiry, validate corporate actions/splits and survivorship in the return series, estimate original-time out-of-sample and walk-forward **hedged realized portfolio** outcomes including costs, premium decay, volatility shocks, gap-risk, hedge coverage, horizon and beta stability, prove broker cash/margin/collateral and order ledgers, complete PAPER UI/order-cancel/settlement tests, and separately accept LIVE F&O consent/risk/kill switches. **This PR does not activate PAPER or LIVE or touch AWS/SSM, broker flags, deployment or APK.**

## Evidence

- `backend/src/main/java/in/aitradingsystem/tradingrooms/FnoBetaHedgeOptionsV1.java`
- `backend/src/test/java/in/aitradingsystem/tradingrooms/FnoBetaHedgeOptionsV1Test.java`
- `backend/src/main/resources/mind/capability-manifest.v1.json` (entry `strategy.STR-205`)
- 22 deterministic JUnit cases with intentionally fictional synthetic fixtures, **not real market-data evidence**.

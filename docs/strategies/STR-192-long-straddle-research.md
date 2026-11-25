# STR-192 — Long Straddle (Strategy 23/43)

**Status: DATA_BLOCKED.** This is a deterministic **research-only** evaluator, not an order-routing service. The strategy requires **both option-chain and implied-volatility (IV) data**—unlike directional-only predecessors.

## Structure and expiry risk

Buy one genuine source-listed **CE** and one genuine source-listed **PE** on the same NIFTY underlying, with **identical strike, expiry and option lot size**. Both options are long, so maximum theoretical *expiry* loss is the complete premium paid plus modeled transaction and settlement costs. Upside calls have unlimited theoretical potential as the underlying rises; downside puts have potential up to the strike because NIFTY cannot fall below zero. A volatility opportunity requires movement large enough to overcome **both** premiums and fees; high IV often means options are already expensive.

For `K` = common strike and `Q` = matched lot size:

- `effectiveCallAsk = callAsk × (1 + buySlippageBps/10000)`
- `effectivePutAsk = putAsk × (1 + buySlippageBps/10000)`
- `debitLot = (effectiveCallAsk + effectivePutAsk) × Q + modeledAllInFees`
- `maximumTheoreticalExpiryLoss = debitLot`
- `lowerExpiryBreakeven = K − debitLot/Q`
- `upperExpiryBreakeven = K + debitLot/Q`
- `oneSigmaMoveUnit ≈ sourceSpot × mean(callIV,putIV)/100 × sqrt(calendarDTE/365)`
- `moveToCostBps = 10000 × oneSigmaMoveUnit / (debitLot/Q)`

The annualized IV-to-one-sigma move is **only a transparency heuristic**, not a prediction or backtest. The model rejects candidates where this heuristic is below a configured cost hurdle; it does not imply a profitable strategy or verify the IV smile. As the final expiry approaches the time value can collapse. Fees and mark-to-market timing may make actual liquidation proceeds worse than hypothetical intrinsic payoff.

## Rights, authenticity and as-of restrictions

- Independent research rights to index data, option chain, instrument discovery and **option IV**; do not manufacture IV if the source does not provide a licensed traceable measure. Verify IV unit **percentage** (e.g., 20 means 20%, not 0.20), positive and within configured caps.
- Real listed CE and PE contracts, exactly the same NIFTY underlying, strike, lot and expiry, genuine contract listing/tick and positive strike; illustrative 3–45 days to expiry.
- 35–512 verified completed NSE 15:30 IST daily candles with original first-known source publication, strictly ascending valid sessions, source continuity, no future knowledge. Official NSE holidays and special trading sessions need separate upstream attestation.
- Three independent same-provider quote snapshots: NIFTY spot, CE and PE, positive noncrossed bid/ask, actual **ask-side depth and OI**, original exchange timestamps aged <=180 seconds, <=90 seconds overall skew and original first-known information by `asOf`. The published completed daily close must precede quote exchange events.
- Source-verified near-ATM strike distance, bid-ask spread bound, nonzero modeled two-leg brokerage/India statutory fees, explicit research premium budget. Missing IV, rights, quotes, expiry/lot mapping or freshness => `BLOCKED_DATA` / `BLOCKED_RIGHTS`.

All decisions remain `BLOCKED_DATA`, `BLOCKED_RIGHTS`, `NO_SIGNAL` or `RESEARCH_CANDIDATE`, with `executable=false`, `orderPlaced=false`, `liveTradeAuthorized=false`. The evaluator does not call any broker route.

## Tests and required acceptance evidence

10 Java/JUnit deterministic tests cover valid two-leg ATM research candidate and expiry breakevens, rights, wrong contract CE/PE/strike/expiry/lot, missing/out-of-range IV, crossed/illiquid/cross-provider/stale/future quotes, historical as-of lookahead, budget, IV-implied-move-versus-cost, nonzero fees, and deterministic no-order fields.

**Still required:** licensed real NIFTY CE/PE chains with original quote timestamps and source IV, verified NSE calendar and contract lot/expiry, actual brokerage costs and settlement rights, original as-of out-of-sample/backtest/walk-forward performance and volatility regime tests, separate PAPER lifecycle/UI acceptance and separately approved LIVE broker rights, permissions and kill switches. **Synthetic unit-test fixtures are not production market data.**

See [STR-191 Put Ratio Spread](STR-191-put-ratio-spread-research.md).

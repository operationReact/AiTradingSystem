# STR-171 — Futures Calendar Spread, deterministic research evaluator

**Catalog:** strategy.STR-171 of 43 F&O strategies. **Status: DATA_BLOCKED** until actual authorized source contracts/quotes and backtest/PAPER evidence pass.

## Trading hypothesis (not an execution instruction)

Two exchange-listed futures contracts reference exactly the same NSE index or stock, but expire on different dates (a **near** contract and a **far** contract). A financing/dividend model estimates the theoretical near-to-far price relationship. Compare the *tradable* near bid/ask against the far bid/ask, allowing for explicit round-trip costs:

- **Long near, short far:** far BID − near ASK × [1 + (annual funding − annual dividend yield) × days-between-expiries/365] − two-leg cost.
- **Short near, long far:** near BID × [(annual funding − annual dividend yield) × days-between-expiries/365] − (far ASK − near BID) − two-leg cost.
- Convert net INR/unit to basis points using the relevant near-side price, and generate research candidates only if net exceeds an explicit minimum hurdle.

This is a **model comparison**, not riskless arbitrage: futures term structures vary, a near contract expires before the far, capital/margin and settlement can differ, and the two legs may not fill simultaneously. Discrete dividends/corporate actions, execution impact, taxes, physical delivery (for single-stock futures), financing, margin, liquidity and expiry handling require separately verified modeling.

## Evaluation rules

- Both contracts must be provider-verified NSE_FO instruments, with exactly the same canonical underlying and **equal lot sizes** (no assumed fractional or mismatched hedge ratios).
- Expiries must differ and remain valid; near expiry at least 3 calendar days away and far expiry within 180 days.
- Each quote belongs to its contract key, comes from the **same supported provider**, has valid bid/ask, exchange time + observed time not ahead of evaluation, age <= 15 seconds and cross-leg exchange skew <= 10 seconds.
- Internal research use of each leg, contract-discovery permission and market session must be independently verified by backend. Missing source rights => BLOCKED_RIGHTS; all invalid inputs => BLOCKED_DATA; missing theoretical edge => NO_EDGE.
- Annual financing/dividend-yield and aggregate two-leg round-trip cost assumptions must be supplied explicitly; never assume frictionless futures spread fills.
- Outputs include only a *research* direction and indicative basis-point/lot edge. **Executable=false; orderPlaced=false**. No API, WebSocket, DB, strategy scheduler, PAPER order, or LIVE broker order is invoked.

## Validation and remaining work

JUnit cases cover rich/cheap far, normal no-edge, each leg's rights, market closure, bad expiry/underlying/lot/key/source, stale/future-known ticks, cross-leg skew, missing costs and deterministic results. Tests do not establish exchange-data readiness or profitability.

Before using in MintTick rooms: integrate the single rights-checked canonical snapshot adapter for both futures contracts, verify real broker source and historical multi-expiry data, add as-of costed replay/backtest and out-of-sample assessment, prove separate PAPER lifecycle and reconciliation. LIVE F&O activation is independently pending broker/account approval.

Shared architecture: [F&O canonical strategy feed](../architecture/fno-canonical-strategy-feed.md).
